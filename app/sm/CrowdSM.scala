package lila.ws
package sm

import ipc._

object CrowdSM {

  case class State(
      rooms: Map[RoomId, RoomState] = Map.empty,
      emit: List[RoomCrowd] = Nil
  ) {
    def update(roomId: RoomId)(f: RoomState => RoomState) = copy(
      rooms = rooms.updatedWith(roomId) { cur =>
        val newRoom = f(cur getOrElse RoomState())
        if (newRoom.isEmpty) None else Some(newRoom)
      },
      emit = Nil
    )
  }

  case class RoomState(
      anons: Int = 0,
      users: Map[User.ID, Int] = Map.empty
  ) {
    def nbUsers = users.size
    def nbMembers = anons + nbUsers
    def isEmpty = anons == 0 && users.isEmpty
  }

  def apply(state: State, input: Input): State = input match {

    case Connect(roomId, user) => state.update(roomId) { room =>
      room.copy(
        anons = room.anons + (if (user.isDefined) 0 else 1),
        users = user.fold(room.users) { u =>
          room.users.updatedWith(u.id)(cur => Some(cur.fold(1)(_ + 1)))
        }
      )
    }

    case Disconnect(roomId, user) => state.update(roomId) { room =>
      room.copy(
        anons = room.anons - (if (user.isDefined) 0 else 1),
        users = user.fold(room.users) { u =>
          room.users.updatedWith(u.id)(_.map(_ - 1).filter(_ > 0))
        }
      )
    }

    case Publish => state.copy(
      emit = state.rooms.flatMap {
        case (roomId, room) => (room.nbMembers match {
          case 0 => None
          case n if n > 15 => Some(RoomCrowd(roomId, n, Nil, 0))
          case n => Some(RoomCrowd(
            roomId = roomId,
            members = n,
            users = room.users.keys,
            anons = room.anons
          ))
        })
      }.toList
    )
  }

  sealed trait Input
  case class Connect(roomId: RoomId, user: Option[User]) extends Input
  case class Disconnect(roomId: RoomId, user: Option[User]) extends Input
  case object Publish extends Input

  case class RoomCrowd(roomId: RoomId, members: Int, users: Iterable[User.ID], anons: Int)

  def machine = StateMachine[State, Input, RoomCrowd](State(), apply, _.emit)
}
