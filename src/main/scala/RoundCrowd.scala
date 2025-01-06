package lila.ws

import cats.syntax.option.*
import chess.{ ByColor, Color }

import ipc.*

final class RoundCrowd(
    lila: Lila,
    json: CrowdJson,
    groupedWithin: util.GroupedWithin
)(using Executor):

  import RoundCrowd.*

  private val rounds = scalalib.ConcurrentMap[RoomId, RoundState](32768)
  export rounds.size

  def connect(roomId: RoomId, user: Option[User.Id], player: Option[Color]): Unit =
    rounds
      .compute(roomId)(_.getOrElse(RoundState()).connect(user, player).some)
      .foreach(publish(roomId, _))

  def disconnect(roomId: RoomId, user: Option[User.Id], player: Option[Color]): Unit =
    rounds.computeIfPresent(roomId): round =>
      val newRound = round.disconnect(user, player)
      publish(roomId, newRound)
      Option.unless(newRound.isEmpty)(newRound)

  def botOnline(roomId: RoomId, color: Color, online: Boolean): Unit =
    rounds.compute(roomId): cur =>
      cur
        .getOrElse(RoundState())
        .botOnline(color, online)
        .fold(cur): round =>
          publish(roomId, round)
          Option.unless(round.isEmpty)(round)

  def getUsers(roomId: RoomId): Set[User.Id] =
    rounds.get(roomId).fold(Set.empty)(_.room.users.keySet)

  def isPresent(roomId: RoomId, userId: User.Id): Boolean =
    getUsers(roomId).contains(userId)

  private def publish(roomId: RoomId, round: RoundState): Unit =
    outputBatch(outputOf(roomId, round))

  private val outputBatch = groupedWithin[Output](512, 700.millis): outputs =>
    val aggregated = outputs
      .foldLeft(Map.empty[RoomId, Output]): (crowds, crowd) =>
        crowds.updated(crowd.room.roomId, crowd)
      .values
    lila.emit.round(LilaIn.RoundOnlines(aggregated))
    aggregated.foreach: output =>
      json
        .round(output)
        .foreach:
          Bus.publish(_.room(output.room.roomId), _)

object RoundCrowd:

  case class Output(room: RoomCrowd.Output, players: ByColor[Int]):
    def isEmpty = room.members == 0 && players.forall(_ == 0)

  def outputOf(roomId: RoomId, round: RoundState) = Output(
    room = RoomCrowd.outputOf(roomId, round.room),
    players = round.players
  )

  case class RoundState(
      room: RoomCrowd.RoomState = RoomCrowd.RoomState(),
      players: ByColor[Int] = ByColor(0, 0)
  ):
    def connect(user: Option[User.Id], player: Option[Color]) =
      copy(
        room = if player.isDefined then room else room.connect(user),
        players = player.fold(players)(c => players.update(c, _ + 1))
      )
    def disconnect(user: Option[User.Id], player: Option[Color]) =
      copy(
        room = if player.isDefined then room else room.disconnect(user),
        players = player.fold(players)(c => players.update(c, nb => Math.max(0, nb - 1)))
      )
    def botOnline(color: Color, online: Boolean): Option[RoundState] = Some:
      if online then connect(None, Some(color))
      else disconnect(None, Some(color))

    def isEmpty = room.isEmpty && players.forall(1 > _)
