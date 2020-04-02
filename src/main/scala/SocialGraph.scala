package lila.ws

import com.typesafe.config.Config
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.locks.ReentrantLock
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters._
import scala.util.Random

// Best effort fixed capacity cache for the social graph of online users.
//
// Based on a fixed size array with at most 2^logCapacity entries and
// adjacency lists. Reserve space for peak online users and all their followed
// users.
//
// As the number of online users and their followed users approaches the
// capacity, there is a slight chance that tells are missed, or that the list
// of followed users is incomplete.
//
// This collection is thread-safe. To avoid race conditions with
// follow/unfollow and database reads, all changes should be committed to the
// database first.
final class SocialGraph(mongo: Mongo, config: Config) {

  import SocialGraph._

  private val logCapacity = config.getInt("socialGraph.logCapacity")
  private val logNumLocks = config.getInt("socialGraph.logNumLocks")

  private val graph = new Graph()

  // A linear probing, open addressing hash table. A custom implementation is
  // used, so that we know the index of an entry in the hash table is stable
  // (at least until it is replaced).
  private val slotsMask: Int          = (1 << logCapacity) - 1
  private val slots: Array[UserEntry] = new Array(1 << logCapacity)

  // An array of locks, where holding locks[slot & locksMask] means exclusive
  // access to that slot.
  private val locksMask: Int              = (1 << logNumLocks) - 1
  private val locks: Array[ReentrantLock] = Array.tabulate(locksMask + 1)(_ => new ReentrantLock())

  private def lockFor(slot: Int): ReentrantLock = {
    val lock = locks(slot & locksMask)
    lock.lock()
    lock
  }

  @inline
  private def read(slot: Int, _lock: ReentrantLock): Option[UserEntry] = Option(slots(slot))

  @inline
  private def staleRead(slot: Int): Option[UserEntry] = Option(slots(slot))

  @inline
  private def write(slot: Int, _lock: ReentrantLock, entry: UserEntry): Unit = {
    slots(slot) = entry
  }

  // The impact of hash collision based attacks is minimal (kicking a
  // particular slot from the graph, as if they were offline). So instead of
  // using a cryptographically secure and randomized hash, just make it
  // slightly more inconvenient to exploit than String.hashCode().
  private val seed = Random.nextInt
  private def fxhash32(id: User.ID): Int = {
    id.foldLeft(seed) {
      case (state, ch) =>
        (Integer.rotateLeft(state, 5) ^ ch.toInt) * 0x9e3779b9
    }
  }

  private def lockSlot(id: User.ID, exceptSlot: Int): Slot = {
    // Try to find an existing or empty slot between hash and
    // hash + MaxStride.
    val hash = fxhash32(id) & slotsMask
    for (s <- hash to (hash + SocialGraph.MaxStride)) {
      val slot = s & slotsMask
      val lock = lockFor(slot)
      read(slot, lock) match {
        case None => return NewSlot(slot, lock)
        case Some(existing) if existing.id == id =>
          return ExistingSlot(slot, lock, existing)
        case _ => lock.unlock()
      }
    }

    // If no existing or empty slot is available, try to replace an
    // offline slot. If someone is watching the offline slot, and that
    // user goes online before the watcher resubscribes, then that update
    // is lost.
    // Do not replace exceptSlot. This can be used so that a followed user
    // does not replace its follower.
    for (s <- hash to (hash + SocialGraph.MaxStride)) {
      val slot = s & slotsMask
      val lock = lockFor(slot)
      read(slot, lock) match {
        case None => return NewSlot(slot, lock)
        case Some(existing) if existing.id == id =>
          return ExistingSlot(slot, lock, existing)
        case Some(existing) if !existing.meta.online && slot != exceptSlot =>
          return freeSlot(slot, lock)
        case _ => lock.unlock()
      }
    }

    // The hashtable is full. Overwrite a random entry.
    val slot = if (hash != exceptSlot) hash else (hash + 1) & slotsMask
    freeSlot(slot, lockFor(slot))
  }

  private def freeSlot(leftSlot: Int, leftLock: ReentrantLock): NewSlot = {
    // Clear all outgoing edges: A freed slot does not follow anyone.
    graph.readOutgoing(leftSlot, leftLock) foreach { graph.remove(leftSlot, leftLock, _) }

    // Clear all incoming edges and mark everyone who followed this slot stale.
    graph.readIncompleteIncoming(leftSlot, leftLock) foreach { rightSlot =>
      val rightLock = lockFor(rightSlot)
      read(rightSlot, rightLock) foreach { rightEntry =>
        write(rightSlot, rightLock, rightEntry.update(_.withFresh(false)))
      }
      graph.remove(rightSlot, rightLock, leftSlot)
      rightLock.unlock()
    }

    write(leftSlot, leftLock, null)
    NewSlot(leftSlot, leftLock)
  }

  private def readFollowed(leftSlot: Int, leftLock: ReentrantLock): List[UserEntry] = {
    graph.readOutgoing(leftSlot, leftLock) flatMap { rightSlot =>
      staleRead(rightSlot) map { entry =>
        UserEntry(entry.id, entry.meta)
      }
    }
  }

  private def readOnlineFollowing(leftSlot: Int, leftLock: ReentrantLock): List[User.ID] = {
    graph.readIncompleteIncoming(leftSlot, leftLock) flatMap { rightSlot =>
      staleRead(rightSlot) collect {
        case entry if entry.meta.online && entry.meta.subscribed => entry.id
      }
    }
  }

  private def updateFollowed(
      leftSlot: Int,
      leftLock: ReentrantLock,
      followed: Iterable[User.ID]
  ): List[UserEntry] = {
    graph.readOutgoing(leftSlot, leftLock) foreach { graph.remove(leftSlot, leftLock, _) }

    (followed map { userId =>
      val ((rightSlot, rightLock), info) = lockSlot(userId, leftSlot) match {
        case NewSlot(rightSlot, rightLock) =>
          write(rightSlot, rightLock, UserEntry(userId, UserMeta.stale))
          rightSlot -> rightLock -> UserEntry(userId, UserMeta.stale)
        case ExistingSlot(rightSlot, rightLock, entry) =>
          write(rightSlot, rightLock, entry)
          rightSlot -> rightLock -> UserEntry(userId, entry.meta)
      }
      graph.add(leftSlot, leftLock, rightSlot, rightLock)
      rightLock.unlock()
      info
    }).toList
  }

  private def doLoadFollowed(id: User.ID)(implicit ec: ExecutionContext): Future[List[UserEntry]] = {
    mongo.loadFollowed(id) map { followed =>
      val (leftSlot, leftLock) = lockSlot(id, -1) match {
        case NewSlot(leftSlot, leftLock) =>
          write(leftSlot, leftLock, UserEntry(id, UserMeta.freshSubscribed))
          leftSlot -> leftLock
        case ExistingSlot(leftSlot, leftLock, entry) =>
          write(leftSlot, leftLock, entry.update(_.withFresh(true).withSubscribed(true)))
          leftSlot -> leftLock
      }
      val infos = updateFollowed(leftSlot, leftLock, followed)
      leftLock.unlock()
      infos
    }
  }

  // Load users that id follows, either from the cache or from the database,
  // and subscribes to future updates from tell.
  def followed(id: User.ID)(implicit ec: ExecutionContext): Future[List[UserEntry]] = {
    val (infos, lock) = lockSlot(id, -1) match {
      case NewSlot(slot, lock) =>
        None -> lock
      case ExistingSlot(slot, lock, entry) =>
        if (entry.meta.fresh) {
          write(slot, lock, entry.update(_.withSubscribed(true)))
          Some(readFollowed(slot, lock)) -> lock
        } else None -> lock
    }
    lock.unlock()
    infos.fold(doLoadFollowed(id))(Future.successful _)
  }

  def unsubscribe(id: User.ID): Unit = {
    (lockSlot(id, -1) match {
      case NewSlot(slot, lock) => lock
      case ExistingSlot(slot, lock, entry) =>
        write(slot, lock, entry.update(_.withSubscribed(false)))
        lock
    }).unlock()
  }

  // left no longer follows right.
  def unfollow(left: User.ID, right: User.ID): Unit = {
    (lockSlot(left, -1) match {
      case ExistingSlot(leftSlot, leftLock, _) =>
        (lockSlot(right, leftSlot) match {
          case ExistingSlot(rightSlot, rightLock, _) =>
            graph.remove(leftSlot, leftLock, rightSlot)
            rightLock
          case NewSlot(_, rightLock) => rightLock
        }).unlock()
        leftLock
      case NewSlot(_, leftLock) => leftLock
    }).unlock()
  }

  // left now follows right.
  def follow(left: User.ID, right: User.ID): Unit = {
    (lockSlot(left, -1) match {
      case ExistingSlot(leftSlot, leftLock, _) =>
        val (rightSlot, rightLock) = lockSlot(right, leftSlot) match {
          case ExistingSlot(rightSlot, rightLock, rightEntry) =>
            write(rightSlot, rightLock, rightEntry)
            rightSlot -> rightLock
          case NewSlot(rightSlot, rightLock) =>
            write(rightSlot, rightLock, UserEntry(right, UserMeta.stale))
            rightSlot -> rightLock
        }
        graph.add(leftSlot, leftLock, rightSlot, rightLock)
        rightLock.unlock()
        leftLock
      case NewSlot(_, leftLock) =>
        // Do nothing. Next followed will have to hit the database anyway.
        leftLock
    }).unlock()
  }

  // Updates the status of a user. Returns the current user info and a list of
  // subscribed users that are interested in this update (if any).
  def tell(id: User.ID, meta: UserMeta => UserMeta): Option[(SocialGraph.UserEntry, List[User.ID])] = {
    val (result, lock) = lockSlot(id, -1) match {
      case ExistingSlot(slot, lock, entry) =>
        val newEntry = entry.update(meta)
        write(slot, lock, newEntry)
        val result = UserEntry(entry.id, newEntry.meta) -> readOnlineFollowing(slot, lock)
        Some(result) -> lock
      case NewSlot(slot, lock) =>
        write(slot, lock, UserEntry(id, meta(UserMeta.stale)))
        None -> lock
    }
    lock.unlock()
    result
  }
}

object SocialGraph {

  private val MaxStride: Int = 20

  case class UserMeta private (flags: Int) extends AnyVal {
    @inline
    private def toggle(flag: Int, on: Boolean) = UserMeta(if (on) flags | flag else flags & ~flag)
    @inline
    private def has(flag: Int): Boolean = (flags & flag) != 0

    def fresh      = has(UserMeta.FRESH)
    def subscribed = has(UserMeta.SUBSCRIBED)
    def online     = has(UserMeta.ONLINE)
    def playing    = has(UserMeta.PLAYING)

    def withFresh(fresh: Boolean)           = toggle(UserMeta.FRESH, fresh)
    def withSubscribed(subscribed: Boolean) = toggle(UserMeta.SUBSCRIBED, subscribed)
    def withOnline(online: Boolean)         = toggle(UserMeta.ONLINE, online)
    def withPlaying(playing: Boolean)       = toggle(UserMeta.PLAYING, playing)
  }
  object UserMeta {
    private val FRESH      = 1
    private val SUBSCRIBED = 2
    private val ONLINE     = 4
    private val PLAYING    = 8
    val stale              = UserMeta(0)
    val freshSubscribed    = UserMeta(FRESH | SUBSCRIBED)
  }

  case class UserEntry(id: User.ID, meta: UserMeta) {
    def update(f: UserMeta => UserMeta) = copy(meta = f(meta))
  }

  sealed private trait Slot
  private case class NewSlot(slot: Int, lock: ReentrantLock)                        extends Slot
  private case class ExistingSlot(slot: Int, lock: ReentrantLock, entry: UserEntry) extends Slot

  private class AdjacencyList {
    private val inner: ConcurrentSkipListSet[Long] = new ConcurrentSkipListSet()

    def add(a: Int, b: Int): Unit    = inner.add(AdjacencyList.makePair(a, b))
    def remove(a: Int, b: Int): Unit = inner.remove(AdjacencyList.makePair(a, b))
    def has(a: Int, b: Int): Boolean = inner.contains(AdjacencyList.makePair(a, b))

    def read(a: Int): List[Int] =
      inner
        .subSet(AdjacencyList.makePair(a, 0), AdjacencyList.makePair(a + 1, 0))
        .asScala
        .map { entry =>
          entry.toInt & 0xffffffff
        }
        .toList
  }

  private class Graph {
    private val outgoing = new AdjacencyList()
    private val incoming = new AdjacencyList()

    def readOutgoing(a: Int, lockA: ReentrantLock): List[Int] = outgoing.read(a)

    def readIncompleteIncoming(a: Int, _lockA: ReentrantLock): List[Int] = incoming.read(a)

    def add(a: Int, _lockA: ReentrantLock, b: Int, _lockB: ReentrantLock): Unit = {
      outgoing.add(a, b)
      incoming.add(b, a)
    }

    def remove(a: Int, _lockA: ReentrantLock, b: Int): Unit = {
      outgoing.remove(a, b)
      incoming.remove(b, a)
    }
  }

  private object AdjacencyList {
    @inline
    private def makePair(a: Int, b: Int): Long = (a.toLong << 32) | b.toLong
  }
}
