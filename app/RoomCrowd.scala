package lila.ws

import java.util.concurrent.ConcurrentHashMap
import javax.inject._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import ipc._

@Singleton
final class RoomCrowd @Inject() (
    json: CrowdJson,
    groupedWithin: GroupedWithin
)(implicit ec: ExecutionContext) {

  import RoomCrowd._

  private val rooms = new ConcurrentHashMap[RoomId, RoomState](1024)

  def connect(roomId: RoomId, user: Option[User]): Unit = publish(
    roomId,
    rooms.compute(roomId, (_, cur) => Option(cur).getOrElse(RoomState()) connect user)
  )

  def disconnect(roomId: RoomId, user: Option[User]): Unit = {
    val room = rooms.computeIfPresent(roomId, (_, room) => {
      val newRoom = room disconnect user
      if (room.isEmpty) null else room
    })
    if (room != null) publish(roomId, room)
  }

  def getUsers(roomId: RoomId): Set[User.ID] =
    Option(rooms get roomId).fold(Set.empty[User.ID])(_.users.keySet)

  def isPresent(roomId: RoomId, userId: User.ID): Boolean =
    Option(rooms get roomId).exists(_.users contains userId)

  private def publish(roomId: RoomId, room: RoomState): Unit =
    outputBatch(outputOf(roomId, room))

  private val outputBatch = groupedWithin[Output](1024, 1.second) { outputs =>
    outputs.foldLeft(Map.empty[RoomId, Output]) {
      case (crowds, crowd) => crowds.updated(crowd.roomId, crowd)
    }.values foreach { output =>
      json room output foreach {
        Bus.publish(_, _ room output.roomId)
      }
    }
  }

  def size = rooms.size
}

object RoomCrowd {

  case class Output(
      roomId: RoomId,
      members: Int,
      users: Iterable[User.ID],
      anons: Int
  )

  def outputOf(roomId: RoomId, room: RoomState) = Output(
    roomId = roomId,
    members = room.nbMembers,
    users = room.users.keys,
    anons = room.anons
  )

  case class RoomState(
      anons: Int = 0,
      users: Map[User.ID, Int] = Map.empty
  ) {
    def nbUsers = users.size
    def nbMembers = anons + nbUsers
    def isEmpty = nbMembers < 1

    def connect(user: Option[User]) = copy(
      anons = anons + (if (user.isDefined) 0 else 1),
      users = user.fold(users) { u =>
        users.updatedWith(u.id)(cur => Some(cur.fold(1)(_ + 1)))
      }
    )
    def disconnect(user: Option[User]) = copy(
      anons = anons - (if (user.isDefined) 0 else 1),
      users = user.fold(users) { u =>
        users.updatedWith(u.id)(_.map(_ - 1).filter(_ > 0))
      }
    )
  }
}
