package lila.ws

import chess.Color
import java.util.concurrent.ConcurrentHashMap

import ipc._

object RoundCrowd {

  case class RoundState(
      room: RoomCrowd.RoomState = RoomCrowd.RoomState(),
      players: Color.Map[Int] = Color.Map(0, 0)
  ) {
    def connect(user: Option[User], player: Option[Color]) = copy(
      room = if (player.isDefined) room else room connect user,
      players = player.fold(players)(c => players.update(c, _ + 1))
    )
    def disconnect(user: Option[User], player: Option[Color]) = copy(
      room = if (player.isDefined) room else room disconnect user,
      players = player.fold(players)(c => players.update(c, _ - 1))
    )
    def isEmpty = room.isEmpty && players.forall(1 > _)
  }

  case class Output(
      room: RoomCrowd.Output,
      players: Color.Map[Int]
  ) {
    def isEmpty = room.members == 0 && players.white == 0 && players.black == 0
  }

  sealed trait Input
  case class Connect(roomId: RoomId, user: Option[User], player: Option[Color]) extends Input
  case class Disconnect(roomId: RoomId, user: Option[User], player: Option[Color]) extends Input

  private val rooms = new ConcurrentHashMap[RoomId, RoundState]

  def apply(in: Input): Option[Output] = in match {

    case Connect(roomId, user, player) => Some {
      outputOf(
        roomId,
        rooms.compute(roomId, (_, cur) => Option(cur).getOrElse(RoundState()).connect(user, player))
      )
    }

    case Disconnect(roomId, user, player) =>
      val room = rooms.compute(roomId, (_, cur) => Option(cur).fold(RoundState())(_.disconnect(user, player)))
      if (room.isEmpty) rooms remove roomId
      Some(outputOf(roomId, room))
  }

  def getUsers(roomId: RoomId): Set[User.ID] =
    Option(rooms get roomId).fold(Set.empty[User.ID])(_.room.users.keySet)

  def isPresent(roomId: RoomId, userId: User.ID): Boolean =
    Option(rooms get roomId).exists(_.room.users contains userId)

  private def outputOf(roomId: RoomId, round: RoundState) = Output(
    room = RoomCrowd.outputOf(roomId, round.room),
    players = round.players
  )
}

