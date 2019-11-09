package lila.ws

import java.util.concurrent.ConcurrentHashMap

import ipc._

object RoomCrowd {

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

  case class Output(
      roomId: RoomId,
      members: Int,
      users: Iterable[User.ID],
      anons: Int
  )

  sealed trait Input
  case class Connect(roomId: RoomId, user: Option[User]) extends Input
  case class Disconnect(roomId: RoomId, user: Option[User]) extends Input

  private val rooms = new ConcurrentHashMap[RoomId, RoomState](1024)

  def apply(in: Input): Option[Output] = in match {

    case Connect(roomId, user) => Some {
      outputOf(
        roomId,
        rooms.compute(roomId, (_, cur) => Option(cur).getOrElse(RoomState()) connect user)
      )
    }

    case Disconnect(roomId, user) =>
      val room = rooms.compute(roomId, (_, cur) => Option(cur).fold(RoomState())(_ disconnect user))
      if (room.isEmpty) {
        rooms remove roomId
        None
      }
      else Some(outputOf(roomId, room))
  }

  def getUsers(roomId: RoomId): Set[User.ID] =
    Option(rooms get roomId).fold(Set.empty[User.ID])(_.users.keySet)

  def isPresent(roomId: RoomId, userId: User.ID): Boolean =
    Option(rooms get roomId).exists(_.users contains userId)

  def outputOf(roomId: RoomId, room: RoomState) = Output(
    roomId = roomId,
    members = room.nbMembers,
    users = room.users.keys,
    anons = room.anons
  )
}
