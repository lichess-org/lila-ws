package lila.ws

import chess.{ ByColor, Color }
import java.util.concurrent.ConcurrentHashMap

import ipc.*

final class RoundCrowd(
    lila: Lila,
    json: CrowdJson,
    groupedWithin: util.GroupedWithin
)(using Executor):

  import RoundCrowd.*

  private val rounds = ConcurrentHashMap[RoomId, RoundState](32768)

  def connect(roomId: RoomId, user: Option[User.Id], player: Option[Color]): Unit =
    publish(
      roomId,
      rounds.compute(roomId, (_, cur) => Option(cur).getOrElse(RoundState()).connect(user, player))
    )

  def disconnect(roomId: RoomId, user: Option[User.Id], player: Option[Color]): Unit =
    rounds.computeIfPresent(
      roomId,
      (_, round) =>
        val newRound = round.disconnect(user, player)
        publish(roomId, newRound)
        if newRound.isEmpty then null else newRound
    )

  def botOnline(roomId: RoomId, color: Color, online: Boolean): Unit =
    rounds.compute(
      roomId,
      (_, cur) =>
        Option(cur).getOrElse(RoundState()).botOnline(color, online) match
          case None => cur
          case Some(round) =>
            publish(roomId, round)
            if round.isEmpty then null else round
    )

  def getUsers(roomId: RoomId): Set[User.Id] =
    Option(rounds get roomId).fold(Set.empty[User.Id])(_.room.users.keySet)

  def isPresent(roomId: RoomId, userId: User.Id): Boolean =
    Option(rounds get roomId).exists(_.room.users contains userId)

  private def publish(roomId: RoomId, round: RoundState): Unit =
    outputBatch(outputOf(roomId, round))

  private val outputBatch = groupedWithin[Output](512, 700.millis): outputs =>
    val aggregated = outputs
      .foldLeft(Map.empty[RoomId, Output]): (crowds, crowd) =>
        crowds.updated(crowd.room.roomId, crowd)
      .values
    lila.emit.round(LilaIn.RoundOnlines(aggregated))
    aggregated.foreach: output =>
      json round output foreach:
        Bus.publish(_ room output.room.roomId, _)

  def size = rounds.size

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
        room = if player.isDefined then room else room connect user,
        players = player.fold(players)(c => players.update(c, _ + 1))
      )
    def disconnect(user: Option[User.Id], player: Option[Color]) =
      copy(
        room = if player.isDefined then room else room disconnect user,
        players = player.fold(players)(c => players.update(c, nb => Math.max(0, nb - 1)))
      )
    def botOnline(color: Color, online: Boolean): Option[RoundState] = Some:
      if online then connect(None, Some(color))
      else disconnect(None, Some(color))

    def isEmpty = room.isEmpty && players.forall(1 > _)
