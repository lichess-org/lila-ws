package lila.ws

import com.typesafe.config.Config
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.locks.ReentrantLock
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters._

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

  private def lockSlot(id: User.ID, exceptSlot: Int): Slot = {
    // Try to find an existing or empty slot between hash and
    // hash + MaxStride.
    val hash = id.hashCode & slotsMask
    for (s <- hash to (hash + SocialGraph.MaxStride)) {
      val slot = s & slotsMask
      if (slot != exceptSlot) {
        val lock = lockFor(slot)
        read(slot, lock) match {
          case None => return NewSlot(slot, lock)
          case Some(existing) if existing.id == id =>
            return ExistingSlot(slot, lock, existing)
          case _ => lock.unlock()
        }
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
      if (slot != exceptSlot) {
        val lock = lockFor(slot)
        read(slot, lock) match {
          case None => return NewSlot(slot, lock)
          case Some(existing) if existing.id == id =>
            return ExistingSlot(slot, lock, existing)
          case Some(existing) if !existing.meta.exists(_.online) =>
            return freeSlot(slot, lock)
          case _ => lock.unlock()
        }
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
        write(rightSlot, rightLock, rightEntry.copy(fresh = false))
      }
      graph.remove(rightSlot, rightLock, leftSlot)
      rightLock.unlock()
    }

    write(leftSlot, leftLock, null)
    NewSlot(leftSlot, leftLock)
  }

  private def readFollowed(leftSlot: Int, leftLock: ReentrantLock): List[UserInfo] = {
    graph.readOutgoing(leftSlot, leftLock) flatMap { rightSlot =>
      staleRead(rightSlot) flatMap { entry =>
        entry.data map { UserInfo(entry.id, _, entry.meta) }
      }
    }
  }

  private def readOnlineFollowing(leftSlot: Int, leftLock: ReentrantLock): List[User.ID] = {
    graph.readIncompleteIncoming(leftSlot, leftLock) flatMap { rightSlot =>
      staleRead(rightSlot) collect {
        case entry if entry.meta.exists(_.online) => entry.id
      }
    }
  }

  private def updateFollowed(
      leftSlot: Int,
      leftLock: ReentrantLock,
      followed: Iterable[UserRecord]
  ): List[UserInfo] = {
    graph.readOutgoing(leftSlot, leftLock) foreach { graph.remove(leftSlot, leftLock, _) }

    (followed map { record =>
      val ((rightSlot, rightLock), info) = lockSlot(record.id, leftSlot) match {
        case NewSlot(rightSlot, rightLock) =>
          write(rightSlot, rightLock, UserEntry(record.id, Some(record.data), None, false))
          rightSlot -> rightLock -> UserInfo(record.id, record.data, None)
        case ExistingSlot(rightSlot, rightLock, entry) =>
          write(rightSlot, rightLock, entry.copy(data = Some(record.data)))
          rightSlot -> rightLock -> UserInfo(record.id, record.data, entry.meta)
      }
      graph.add(leftSlot, leftLock, rightSlot, rightLock)
      rightLock.unlock()
      info
    }).toList
  }

  private def doLoadFollowed(id: User.ID)(implicit ec: ExecutionContext): Future[List[UserInfo]] = {
    mongo.loadFollowed(id) map { followed =>
      val (leftSlot, leftLock) = lockSlot(id, -1) match {
        case NewSlot(leftSlot, leftLock) =>
          write(leftSlot, leftLock, UserEntry(id, None, None, true))
          leftSlot -> leftLock
        case ExistingSlot(leftSlot, leftLock, entry) =>
          write(leftSlot, leftLock, entry.copy(fresh = true))
          leftSlot -> leftLock
      }
      val infos = updateFollowed(leftSlot, leftLock, followed)
      leftLock.unlock()
      infos
    }
  }

  // Load users that id follows, either from the cache or from the database,
  // and subscribes to future updates from tell.
  def followed(id: User.ID)(implicit ec: ExecutionContext): Future[List[UserInfo]] = {
    val (infos, lock) = lockSlot(id, -1) match {
      case NewSlot(slot, lock) =>
        None -> lock
      case ExistingSlot(slot, lock, entry) =>
        if (entry.fresh) Some(readFollowed(slot, lock)) -> lock
        else None                                       -> lock
    }
    lock.unlock()
    infos.fold(doLoadFollowed(id))(Future.successful _)
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
  def follow(left: User.ID, right: UserRecord): Unit = {
    (lockSlot(left, -1) match {
      case ExistingSlot(leftSlot, leftLock, _) =>
        val (rightSlot, rightLock) = lockSlot(right.id, leftSlot) match {
          case ExistingSlot(rightSlot, rightLock, rightEntry) =>
            write(rightSlot, rightLock, rightEntry.copy(data = Some(right.data)))
            rightSlot -> rightLock
          case NewSlot(rightSlot, rightLock) =>
            write(rightSlot, rightLock, UserEntry(right.id, Some(right.data), None, false))
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
  def tell(id: User.ID, meta: UserMeta => UserMeta): Option[(SocialGraph.UserInfo, List[User.ID])] = {
    val (result, lock) = lockSlot(id, -1) match {
      case ExistingSlot(slot, lock, entry) =>
        val newEntry = entry.updateMeta(meta)
        write(slot, lock, newEntry)
        (entry.data map { data =>
          UserInfo(entry.id, data, newEntry.meta) -> readOnlineFollowing(slot, lock)
        }) -> lock
      case NewSlot(slot, lock) =>
        write(slot, lock, UserEntry(id, None, None, false).updateMeta(meta))
        None -> lock
    }
    lock.unlock()
    result
  }
}

object SocialGraph {

  private val MaxStride: Int = 20

  case class UserData(name: String, title: Option[String], patron: Boolean) {
    def titleName = title.fold(name)(_ + " " + name)
  }
  case class UserMeta(online: Boolean, playing: Boolean, studying: Boolean)
  case class UserRecord(id: User.ID, data: UserData)
  case class UserInfo(id: User.ID, data: UserData, meta: Option[UserMeta])

  private val defaultMeta = UserMeta(online = false, playing = false, studying = false)

  private case class UserEntry(id: User.ID, data: Option[UserData], meta: Option[UserMeta], fresh: Boolean) {
    def updateMeta(f: UserMeta => UserMeta) = copy(meta = Some(f(meta getOrElse defaultMeta)))
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
