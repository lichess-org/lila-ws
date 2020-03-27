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
// Except thread-safety and no runtime errors, almost nothing is guaranteed.
// As the number of online users and their followed users approaches the
// capacity, there is a slight chance that tells are missed.
// Further, if a user is replaced by one of their followers, there is a small
// chance that a tell goes to the wrong user. This is unlikely, and its even
// more unlikely that the wrong user is online to witness it.
final class SocialGraph(mongo: Mongo, config: Config) {

  import SocialGraph._

  private val logCapacity = config.getInt("socialGraph.logCapacity")
  private val logNumLocks = config.getInt("socialGraph.logNumLocks")

  // Adjacency lists, each representing a set of tuples
  // (left slot, right slot).
  private val leftFollowsRight = new AdjacencyList()
  private val rightFollowsLeft = new AdjacencyList()

  // A linear probing, open addressing hash table. A custom implementation is
  // used, so that we know the index of an entry in the hash table is stable
  // (at least until it is replaced).
  private val slotsMask: Int          = (1 << logCapacity) - 1
  private val slots: Array[UserEntry] = new Array(1 << logCapacity)

  // An array of locks, where locks[slot & locksMask] is responsible for that
  // slot. For writes to a slot, hold its lock. For reliable reads from an
  // adjacency list, hold the lock of the left slot. For writes, hold both
  // locks.
  private val locksMask: Int              = (1 << logNumLocks) - 1
  private val locks: Array[ReentrantLock] = Array.tabulate(locksMask + 1)(_ => new ReentrantLock())

  private def lockFor(slot: Int): ReentrantLock = {
    val lock = locks(slot & locksMask)
    lock.lock()
    lock
  }

  private def lockSlot(id: User.ID): Slot = {
    // Try to find an existing or empty slot between hash and
    // hash + MaxStride.
    val hash = id.hashCode & slotsMask
    for (s <- hash to (hash + SocialGraph.MaxStride)) {
      val slot = s & slotsMask
      val lock = lockFor(slot)
      if (slots(slot) == null) return NewSlot(slot, lock)
      else if (slots(slot).id == id) return ExistingSlot(slot, lock)
      else lock.unlock()
    }

    // If no exisiting or empty slot is available, try to replace an
    // offline slot. If someone is watching the offline slot, and that
    // user goes online before the watcher resubscribes, then that update
    // is lost.
    for (s <- hash to (hash + SocialGraph.MaxStride)) {
      val slot     = s & slotsMask
      val lock     = lockFor(slot)
      val existing = slots(slot)
      if (existing == null) return NewSlot(slot, lock)
      else if (existing.id == id) return ExistingSlot(slot, lock)
      else if (!existing.meta.exists(_.online)) return freeSlot(slot, lock)
      else lock.unlock()
    }

    // The hashtable is full. Overwrite a random entry.
    freeSlot(hash, lockFor(hash))
  }

  private def freeSlot(leftSlot: Int, leftLock: ReentrantLock): NewSlot = {
    leftFollowsRight.read(leftSlot) foreach { rightSlot =>
      val rightLock = lockFor(rightSlot)
      slots(rightSlot) = slots(rightSlot).copy(fresh = false)
      leftFollowsRight.remove(leftSlot, rightSlot)
      rightFollowsLeft.remove(rightSlot, leftSlot)
      rightLock.unlock()
    }
    slots(leftSlot) = null
    NewSlot(leftSlot, leftLock)
  }

  private def readFollowed(leftSlot: Int): List[UserInfo] = {
    leftFollowsRight.read(leftSlot) flatMap { rightSlot =>
      Option(slots(rightSlot)) flatMap { entry =>
        entry.data map { UserInfo(entry.id, _, entry.meta) }
      }
    }
  }

  private def readFollowing(leftSlot: Int): List[User.ID] = {
    rightFollowsLeft.read(leftSlot) flatMap { rightSlot =>
      val rightLock = lockFor(rightSlot)
      val id =
        if (leftFollowsRight.has(rightSlot, leftSlot)) Some(slots(rightSlot).id)
        else None
      rightLock.unlock()
      id
    }
  }

  private def updateFollowed(leftSlot: Int, followed: Iterable[UserRecord]): List[UserInfo] = {
    leftFollowsRight.read(leftSlot) foreach { rightSlot =>
      val rightLock = lockFor(rightSlot)
      leftFollowsRight.remove(leftSlot, rightSlot)
      rightFollowsLeft.remove(rightSlot, leftSlot)
      rightLock.unlock()
    }

    (followed map { record =>
      val ((rightSlot, rightLock), info) = lockSlot(record.id) match {
        case NewSlot(rightSlot, rightLock) =>
          slots(rightSlot) = UserEntry(record.id, Some(record.data), None, false)
          rightSlot -> rightLock -> UserInfo(record.id, record.data, None)
        case ExistingSlot(rightSlot, rightLock) =>
          val entry = slots(rightSlot).copy(data = Some(record.data))
          slots(rightSlot) = entry
          rightSlot -> rightLock -> UserInfo(record.id, record.data, entry.meta)
      }
      leftFollowsRight.add(leftSlot, rightSlot)
      rightFollowsLeft.add(rightSlot, leftSlot)
      rightLock.unlock()
      info
    }).toList
  }

  private def doLoadFollowed(id: User.ID)(implicit ec: ExecutionContext): Future[List[UserInfo]] = {
    mongo.loadFollowed(id) map { followed =>
      val (leftSlot, leftLock) = lockSlot(id) match {
        case NewSlot(leftSlot, leftLock) =>
          slots(leftSlot) = UserEntry(id, None, None, true)
          leftSlot -> leftLock
        case ExistingSlot(leftSlot, leftLock) =>
          slots(leftSlot) = slots(leftSlot).copy(fresh = true)
          leftSlot -> leftLock
      }
      val infos = updateFollowed(leftSlot, followed)
      leftLock.unlock()
      infos
    }
  }

  // Load users that id follows, either from the cache or from the database,
  // and subscribes to future updates from tell.
  def followed(id: User.ID)(implicit ec: ExecutionContext): Future[List[UserInfo]] = {
    val (infos, lock) = lockSlot(id) match {
      case NewSlot(slot, lock) =>
        None -> lock
      case ExistingSlot(slot, lock) =>
        if (slots(slot).fresh) Some(readFollowed(slot)) -> lock
        else None                                       -> lock
    }
    lock.unlock()
    infos.fold(doLoadFollowed(id))(Future.successful _)
  }

  // left no longer follows right.
  def unfollow(left: User.ID, right: User.ID): Unit = {
    (lockSlot(left) match {
      case ExistingSlot(leftSlot, leftLock) =>
        (lockSlot(right) match {
          case ExistingSlot(rightSlot, rightLock) =>
            leftFollowsRight.remove(leftSlot, rightSlot)
            rightFollowsLeft.remove(rightSlot, leftSlot)
            rightLock
          case NewSlot(_, rightLock) => rightLock
        }).unlock()
        leftLock
      case NewSlot(_, leftLock) => leftLock
    }).unlock()
  }

  // left now follows right.
  def follow(left: User.ID, right: UserRecord): Unit = {
    (lockSlot(left) match {
      case ExistingSlot(leftSlot, leftLock) =>
        val (rightSlot, rightLock) = lockSlot(right.id) match {
          case ExistingSlot(rightSlot, rightLock) =>
            slots(rightSlot) = slots(rightSlot).copy(data = Some(right.data))
            rightSlot -> rightLock
          case NewSlot(rightSlot, rightLock) =>
            slots(rightSlot) = UserEntry(right.id, Some(right.data), None, false)
            rightSlot -> rightLock
        }
        leftFollowsRight.add(leftSlot, rightSlot)
        rightFollowsLeft.add(rightSlot, leftSlot)
        rightLock.unlock()
        leftLock
      case NewSlot(_, leftLock) =>
        // Do nothing. Next followed will have to hit the database anyway.
        leftLock
    }).unlock()
  }

  // Updates the status of a user. Returns the list of subscribed users that
  // are interested in this update.
  def tell(id: User.ID, meta: UserMeta): List[User.ID] = {
    val (following, lock) = lockSlot(id) match {
      case ExistingSlot(slot, lock) =>
        slots(slot) = slots(slot).copy(meta = Some(meta))
        readFollowing(slot) -> lock
      case NewSlot(slot, lock) =>
        slots(slot) = UserEntry(id, None, Some(meta), false)
        Nil -> lock
    }
    lock.unlock()
    following
  }
}

object SocialGraph {

  private val MaxStride: Int = 20

  case class UserData(name: String, title: Option[String], patron: Boolean) {
    def titleName = title.fold(name)(_ + " " + name)
  }
  case class UserMeta(online: Boolean)
  case class UserRecord(id: User.ID, data: UserData)
  case class UserInfo(id: User.ID, data: UserData, meta: Option[UserMeta])

  private case class UserEntry(id: User.ID, data: Option[UserData], meta: Option[UserMeta], fresh: Boolean)

  sealed private trait Slot
  private case class NewSlot(slot: Int, lock: ReentrantLock)      extends Slot
  private case class ExistingSlot(slot: Int, lock: ReentrantLock) extends Slot

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

  private object AdjacencyList {
    @inline
    private def makePair(a: Int, b: Int): Long = (a.toLong << 32) | b.toLong
  }
}
