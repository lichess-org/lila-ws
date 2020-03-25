package lila.ws

import scala.jdk.CollectionConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ ExecutionContext, Future }
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.locks.{ Lock, ReentrantLock }

class SocialGraph(
  loadFollowed: User.ID => Future[Iterable[UserRecord]],
  logCapacity: Int
) {
  private val slotMask: Int = (1 << logCapacity) - 1

  private val slots: Array[UserEntry] = new Array(1 << logCapacity)
  private val _lock = new ReentrantLock()

  private val leftFollowingRight = new AdjacenyList()
  private val leftFollowedRight = new AdjacenyList()

  private def lockSlot(id: User.ID): Slot = {
    val lock = lockFor(0) // TODO: More granular locking
    val hash = id.hashCode
    hash to (hash + SocialGraph.MaxStride) map(_ & slotMask) collectFirst {
      case slot if slots(slot) == null => NewSlot(slot, lock)
      case slot if slots(slot).id == id => ExistingSlot(slot, lock)
    } getOrElse NoSlot
  }

  private def lockFor(slot: Int): Lock = {
    _lock.lock()
    _lock
  }

  private def readFollowed(leftSlot: Int): List[UserInfo] = {
    leftFollowedRight.read(leftSlot) flatMap { rightSlot =>
      val entry = slots(rightSlot)
      entry.username map { username => UserInfo(entry.id, username, entry.meta) }
    }
  }

  private def readFollowing(leftSlot: Int): List[User.ID] = {
    leftFollowingRight.read(leftSlot) flatMap { rightSlot =>
      val rightLock = lockFor(rightSlot)
      val id =
        if (leftFollowedRight.has(rightSlot, leftSlot)) Some(slots(rightSlot).id)
        else None
      rightLock.unlock()
      id
    }
  }

  private def mergeFollowed(leftSlot: Int, followed: Iterable[UserRecord]): List[UserInfo] = {
    val build: ListBuffer[UserInfo] = new ListBuffer()
    followed foreach { record =>
      lockSlot(record.id) match {
        case NewSlot(rightSlot, rightLock) =>
          slots(rightSlot) = UserEntry(record.id, Some(record.username), None, false)
          leftFollowedRight.add(leftSlot, rightSlot)
          leftFollowingRight.add(rightSlot, leftSlot)
          rightLock.unlock()
          build += UserInfo(record.id, record.username, None)
        case ExistingSlot(rightSlot, rightLock) =>
          val entry = slots(rightSlot).copy(username = Some(record.username))
          slots(rightSlot) = entry
          rightLock.unlock()
          build += UserInfo(record.id, record.username, entry.meta)
        case NoSlot => ()
      }
    }
    build.toList
  }

  private def doLoadFollowed(id: User.ID)(implicit ec: ExecutionContext): Future[List[UserInfo]] = {
    loadFollowed(id) map { followed =>
      lockSlot(id) match {
        case NewSlot(leftSlot, leftLock) =>
          slots(leftSlot) = UserEntry(id, None, None, true)
          val infos = mergeFollowed(leftSlot, followed)
          leftLock.unlock()
          infos
        case ExistingSlot(leftSlot, leftLock) =>
          slots(leftSlot) = slots(leftSlot).copy(fresh = true)
          val infos = mergeFollowed(leftSlot, followed)
          leftLock.unlock()
          infos
        case NoSlot => Nil
      }
    }
  }

  def followed(id: User.ID)(implicit ec: ExecutionContext): Future[List[UserInfo]] = {
    lockSlot(id) match {
      case NewSlot(slot, lock) =>
        lock.unlock()
        doLoadFollowed(id)
      case ExistingSlot(slot, lock) =>
        if (slots(slot).fresh) {
          val infos = readFollowed(slot)
          lock.unlock()
          Future successful infos
        } else {
          lock.unlock()
          doLoadFollowed(id)
        }
      case NoSlot => Future successful Nil
    }
  }

  private def toggleFollow(on: Boolean)(left: User.ID, right: User.ID): Unit = {
    lockSlot(left) match {
      case ExistingSlot(leftSlot, leftLock) =>
        lockSlot(right) match {
          case ExistingSlot(rightSlot, rightLock) =>
            leftFollowingRight.toggle(on)(leftSlot, rightSlot)
            leftFollowedRight.toggle(on)(rightSlot, leftSlot)
            rightLock.unlock()
          case NewSlot(_, rightLock) =>
            rightLock.unlock()
          case NoSlot =>
        }
        leftLock.unlock()
      case NewSlot(_, leftLock) =>
        leftLock.unlock()
      case NoSlot =>
    }
  }

  def follow(left: User.ID, right: User.ID) = toggleFollow(true)(left, right)
  def unfollow(left: User.ID, right: User.ID) = toggleFollow(false)(left, right)

  def tell(id: User.ID, meta: UserMeta): List[User.ID] = {
    lockSlot(id) match {
      case ExistingSlot(slot, lock) =>
        slots(slot) = slots(slot).copy(meta = Some(meta))
        val followed = readFollowing(slot)
        lock.unlock()
        followed
      case NewSlot(slot, lock) =>
        slots(slot) = UserEntry(id, None, Some(meta), false)
        lock.unlock()
        Nil
      case NoSlot => Nil
    }
  }
}

class AdjacenyList {
  private val inner: ConcurrentSkipListSet[Long] = new ConcurrentSkipListSet()

  def toggle(on: Boolean) = if (on) add _ else remove _
  def add(a: Int, b: Int): Unit = inner.add(AdjacenyList.makePair(a, b))
  def remove(a: Int, b: Int): Unit = inner.remove(AdjacenyList.makePair(a, b))
  def has(a: Int, b: Int): Boolean = inner.contains(AdjacenyList.makePair(a, b))

  def read(a: Int): List[Int] =
    inner.subSet(AdjacenyList.makePair(a, 0), AdjacenyList.makePair(a + 1, 0)).asScala.map { entry =>
      entry.toInt & 0xffffffff
    }.toList
}

object AdjacenyList {
  private def makePair(a: Int, b: Int): Long = (a.toLong << 32) | b.toLong
}

case class UserMeta(online: Boolean)
case class UserRecord(id: User.ID, username: String)
case class UserInfo(id: User.ID, username: String, meta: Option[UserMeta])

private case class UserEntry(id: User.ID, username: Option[String], meta: Option[UserMeta], fresh: Boolean)

private sealed trait Slot
private case class NewSlot(slot: Int, lock: Lock) extends Slot
private case class ExistingSlot(slot: Int, lock: Lock) extends Slot
private case object NoSlot extends Slot // TODO: Replace old entry

object SocialGraph {
  private val MaxStride: Int = 20
}
