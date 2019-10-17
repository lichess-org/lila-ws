package lila.ws
package sm

import ipc._

object CrowdSM {

  case class State(
      rooms: Map[RoomId, RoomState] = Map.empty,
      emit: Option[RoomCrowd] = None
  ) {
    def update(roomId: RoomId)(f: RoomState => RoomState) = {
      val room = Some(f(rooms.getOrElse(roomId, RoomState()))).filterNot(_.isEmpty)
      copy(
        rooms = room.fold(rooms - roomId) { rooms.updated(roomId, _) },
        emit = room map { r =>
          RoomCrowd(
            roomId = roomId,
            members = r.nbMembers,
            users = if (r.nbUsers > 15) Nil else r.users.keys,
            anons = r.anons
          )
        }
      )
    }
  }

  case class RoomState(
      anons: Int = 0,
      users: Map[User.ID, Int] = Map.empty
  ) {
    lazy val nbUsers = users.size
    def nbMembers = anons + nbUsers
    def isEmpty = nbMembers < 1
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
  }

  sealed trait Input
  case class Connect(roomId: RoomId, user: Option[User]) extends Input
  case class Disconnect(roomId: RoomId, user: Option[User]) extends Input

  case class RoomCrowd(roomId: RoomId, members: Int, users: Iterable[User.ID], anons: Int)

  def machine = StateMachine[State, Input, RoomCrowd](State(), apply, _.emit.toList)
}
