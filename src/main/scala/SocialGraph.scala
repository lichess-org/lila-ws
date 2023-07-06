package lila.ws

import com.typesafe.config.Config
import java.util.concurrent.locks.ReentrantLock
import scala.jdk.CollectionConverters.*
import scala.util.control.NonLocalReturns.*
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
final class SocialGraph(mongo: Mongo, config: Config):

  import SocialGraph.*
  import Slot.*

  private val logCapacity = config.getInt("socialGraph.logCapacity")

  private val graph = new Graph()

  // A linear probing, open addressing hash table. A custom implementation is
  // used, so that we know the index of an entry in the hash table is stable
  // (at least until it is replaced).
  private val slotsMask: Int          = (1 << logCapacity) - 1
  private val slots: Array[UserEntry] = new Array(1 << logCapacity)

  // Hold this while reading, writing and modifying edge sets.
  private val lock = new ReentrantLock()

  @inline
  private def read(slot: Int): Option[UserEntry] = Option(slots(slot))

  @inline
  private def write(slot: Int, entry: UserEntry): Unit =
    slots(slot) = entry

  // The impact of hash collision based attacks is minimal (kicking a
  // particular slot from the graph, as if they were offline). So instead of
  // using a cryptographically secure and randomized hash, just make it
  // slightly more inconvenient to exploit than String.hashCode().
  private val seed = Random.nextInt()
  private def fxhash32(user: User.Id): Int =
    user.value.foldLeft(seed) { case (state, ch) =>
      (Integer.rotateLeft(state, 5) ^ ch.toInt) * 0x9e3779b9
    }

  private def findSlot(id: User.Id, exceptSlot: Int): Slot = returning[Slot]:
    // Try to find an existing or empty slot between hash and
    // hash + MaxStride.
    val hash = fxhash32(id) & slotsMask
    for s <- hash to (hash + SocialGraph.MaxStride) do
      val slot = s & slotsMask
      read(slot) match
        case None => throwReturn[Slot](NewSlot(slot))
        case Some(existing) if existing.id == id =>
          throwReturn[Slot](ExistingSlot(slot, existing))
        case _ =>

    // If no existing or empty slot is available, try to replace an
    // offline slot. If someone is watching the offline slot, and that
    // user goes online before the watcher resubscribes, then that update
    // is lost.
    // Do not replace exceptSlot. This can be used so that a followed user
    // does not replace its follower.
    for s <- hash to (hash + SocialGraph.MaxStride) do
      val slot = s & slotsMask
      read(slot) match
        case None => throwReturn[Slot](NewSlot(slot))
        case Some(existing) if existing.id == id =>
          throwReturn[Slot](ExistingSlot(slot, existing))
        case Some(existing) if !existing.meta.online && slot != exceptSlot =>
          throwReturn[Slot](freeSlot(slot))
        case _ =>

    // The hashtable is full. Overwrite a random entry.
    val slot = if hash != exceptSlot then hash else (hash + 1) & slotsMask
    freeSlot(slot)

  private def freeSlot(leftSlot: Int): NewSlot =
    // Clear all outgoing edges: A freed slot does not follow anyone.
    graph.readOutgoing(leftSlot) foreach { graph.remove(leftSlot, _) }

    // Clear all incoming edges and mark everyone who followed this slot stale.
    graph.readIncoming(leftSlot) foreach { rightSlot =>
      read(rightSlot) foreach { rightEntry =>
        write(rightSlot, rightEntry.update(_.withFresh(false)))
      }
      graph.remove(rightSlot, leftSlot)
    }

    write(leftSlot, null)
    NewSlot(leftSlot)

  private def readFollowed(leftSlot: Int): List[UserEntry] =
    graph.readOutgoing(leftSlot) flatMap { rightSlot =>
      read(rightSlot) map { entry =>
        UserEntry(entry.id, entry.meta)
      }
    }

  private def readOnlineFollowing(leftSlot: Int): List[User.Id] =
    graph.readIncoming(leftSlot) flatMap { rightSlot =>
      read(rightSlot) collect:
        case entry if entry.meta.online && entry.meta.subscribed => entry.id
    }

  private def updateFollowed(leftSlot: Int, followed: Iterable[User.Id]): List[UserEntry] =
    graph.readOutgoing(leftSlot) foreach { graph.remove(leftSlot, _) }

    (followed map { userId =>
      val (rightSlot, info) = findSlot(userId, leftSlot) match
        case NewSlot(rightSlot) =>
          write(rightSlot, UserEntry(userId, UserMeta.stale))
          rightSlot -> UserEntry(userId, UserMeta.stale)
        case ExistingSlot(rightSlot, entry) =>
          write(rightSlot, entry)
          rightSlot -> UserEntry(userId, entry.meta)
      graph.add(leftSlot, rightSlot)
      info
    }).toList

  private def doLoadFollowed(id: User.Id)(using Executor): Future[List[UserEntry]] =
    mongo.loadFollowed(id) map { followed =>
      lock.lock()
      try
        val leftSlot = findSlot(id, -1) match
          case NewSlot(leftSlot) =>
            write(leftSlot, UserEntry(id, UserMeta.freshSubscribed))
            leftSlot
          case ExistingSlot(leftSlot, entry) =>
            write(leftSlot, entry.update(_.withFresh(true).withSubscribed(true)))
            leftSlot
        updateFollowed(leftSlot, followed)
      finally lock.unlock()
    }

  // Load users that id follows, either from the cache or from the database,
  // and subscribes to future updates from tell.
  def followed(id: User.Id)(using Executor): Future[List[UserEntry]] =
    lock.lock()
    val infos =
      try
        findSlot(id, -1) match
          case NewSlot(_) =>
            None
          case ExistingSlot(slot, entry) =>
            if entry.meta.fresh then
              write(slot, entry.update(_.withSubscribed(true)))
              Some(readFollowed(slot))
            else None
      finally
        lock.unlock()
    infos.fold(doLoadFollowed(id))(Future.successful)

  def unsubscribe(id: User.Id): Unit =
    lock.lock()
    try
      findSlot(id, -1) match
        case ExistingSlot(slot, entry) =>
          write(slot, entry.update(_.withSubscribed(false)))
        case NewSlot(_) =>
    finally
      lock.unlock()

  // left no longer follows right.
  def unfollow(left: User.Id, right: User.Id): Unit =
    lock.lock()
    try
      findSlot(left, -1) match
        case ExistingSlot(leftSlot, _) =>
          findSlot(right, leftSlot) match
            case ExistingSlot(rightSlot, _) =>
              graph.remove(leftSlot, rightSlot)
            case NewSlot(_) =>
        case NewSlot(_) =>
    finally
      lock.unlock()

  // left now follows right.
  def follow(left: User.Id, right: User.Id): Unit =
    lock.lock()
    try
      findSlot(left, -1) match
        case ExistingSlot(leftSlot, _) =>
          val rightSlot = findSlot(right, leftSlot) match
            case ExistingSlot(rightSlot, rightEntry) =>
              write(rightSlot, rightEntry)
              rightSlot
            case NewSlot(rightSlot) =>
              write(rightSlot, UserEntry(right, UserMeta.stale))
              rightSlot
          graph.add(leftSlot, rightSlot)
        case NewSlot(_) => // next followed will have to hit the database anyway
    finally
      lock.unlock()

  // Updates the status of a user. Returns the current user info and a list of
  // subscribed users that are interested in this update (if any).
  def tell(id: User.Id, meta: UserMeta => UserMeta): Option[(SocialGraph.UserEntry, List[User.Id])] =
    lock.lock()
    try
      findSlot(id, -1) match
        case ExistingSlot(slot, entry) =>
          val newEntry = entry.update(meta)
          write(slot, newEntry)
          val result = UserEntry(entry.id, newEntry.meta) -> readOnlineFollowing(slot)
          Some(result)
        case NewSlot(slot) =>
          write(slot, UserEntry(id, meta(UserMeta.stale)))
          None
    finally
      lock.unlock()

object SocialGraph:

  private enum Slot:
    case NewSlot(slot: Int)
    case ExistingSlot(slot: Int, entry: UserEntry)

  private val MaxStride: Int = 16

  opaque type UserMeta = Int
  object UserMeta extends OpaqueInt[UserMeta]:

    private val FRESH      = 1
    private val SUBSCRIBED = 2
    private val ONLINE     = 4
    private val PLAYING    = 8
    val stale              = UserMeta(0)
    val freshSubscribed    = UserMeta(FRESH | SUBSCRIBED)

    extension (flags: UserMeta)
      private inline def toggle(flag: Int, on: Boolean) = UserMeta(if on then flags | flag else flags & ~flag)
      private inline def has(flag: Int): Boolean        = (flags & flag) != 0

      inline def fresh      = flags.has(UserMeta.FRESH)
      inline def subscribed = flags.has(UserMeta.SUBSCRIBED)
      inline def online     = flags.has(UserMeta.ONLINE)
      inline def playing    = flags.has(UserMeta.PLAYING)

      inline def withFresh(fresh: Boolean)           = flags.toggle(UserMeta.FRESH, fresh)
      inline def withSubscribed(subscribed: Boolean) = flags.toggle(UserMeta.SUBSCRIBED, subscribed)
      inline def withOnline(online: Boolean)         = flags.toggle(UserMeta.ONLINE, online)
      inline def withPlaying(playing: Boolean)       = flags.toggle(UserMeta.PLAYING, playing)
  end UserMeta

  case class UserEntry(id: User.Id, meta: UserMeta):
    def update(f: UserMeta => UserMeta) = copy(meta = f(meta))

  private class AdjacencyList:
    private val inner: java.util.TreeSet[Long] = new java.util.TreeSet()

    def add(a: Int, b: Int): Unit    = inner.add(AdjacencyList.makePair(a, b))
    def remove(a: Int, b: Int): Unit = inner.remove(AdjacencyList.makePair(a, b))

    def read(a: Int): List[Int] =
      inner
        .subSet(AdjacencyList.makePair(a, 0), AdjacencyList.makePair(a + 1, 0))
        .asScala
        .map { entry =>
          entry.toInt & 0xffffffff
        }
        .toList

  private class Graph:
    private val outgoing = new AdjacencyList()
    private val incoming = new AdjacencyList()

    def readOutgoing(a: Int): List[Int] = outgoing.read(a)

    def readIncoming(a: Int): List[Int] = incoming.read(a)

    def add(a: Int, b: Int): Unit =
      outgoing.add(a, b)
      incoming.add(b, a)

    def remove(a: Int, b: Int): Unit =
      outgoing.remove(a, b)
      incoming.remove(b, a)

  private object AdjacencyList:
    @inline
    private def makePair(a: Int, b: Int): Long = (a.toLong << 32) | b.toLong
