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

  private val rooms = new ConcurrentHashMap[RoomId, RoomState]

  def apply(in: Input): Option[Output] = in match {

    case Connect(roomId, user) => Some {
      outputOf(
        roomId,
        rooms.compute(roomId, (_, cur: RoomState) => {
          val room = Option(cur).getOrElse(RoomState())
          room.copy(
            anons = room.anons + (if (user.isDefined) 0 else 1),
            users = user.fold(room.users) { u =>
              room.users.updatedWith(u.id)(cur => Some(cur.fold(1)(_ + 1)))
            }
          )
        })
      )
    }

    case Disconnect(roomId: RoomId, user: Option[User]) =>
      val room = rooms.compute(roomId, (_, cur) =>
        Option(cur).fold(RoomState()) { room =>
          room.copy(
            anons = room.anons - (if (user.isDefined) 0 else 1),
            users = user.fold(room.users) { u =>
              room.users.updatedWith(u.id)(_.map(_ - 1).filter(_ > 0))
            }
          )
        })
      if (room.isEmpty) {
        rooms remove roomId
        None
      }
      else Some(outputOf(roomId, room))
  }

  def getUsers(roomId: RoomId): Set[User.ID] =
    Option(rooms get roomId).fold(Set.empty[User.ID])(_.users.keySet)

  private def outputOf(roomId: RoomId, room: RoomState) = Output(
    roomId = roomId,
    members = room.nbMembers,
    users = if (room.nbUsers > 15) Nil else room.users.keys,
    anons = room.anons
  )
}
