package lila.ws

import chess.Color
import java.util.concurrent.ConcurrentHashMap
import javax.inject._
import scala.concurrent.ExecutionContext

import ipc._

@Singleton
final class RoundCrowd @Inject() (json: CrowdJson)(implicit ec: ExecutionContext) {

  import RoundCrowd._

  private val rounds = new ConcurrentHashMap[RoomId, RoundState](32768)

  // TODO RouOns
  def connect(roomId: RoomId, user: Option[User], player: Option[Color]): Unit = publish(
    roomId,
    rounds.compute(roomId, (_, cur) => Option(cur).getOrElse(RoundState()).connect(user, player))
  )

  // TODO RouOns
  def disconnect(roomId: RoomId, user: Option[User], player: Option[Color]): Unit = {
    val round = rounds.computeIfPresent(roomId, (_, round) => {
      val newRound = round.disconnect(user, player)
      if (round.isEmpty) null else round
    })
    if (round != null) publish(roomId, round)
  }

  // TODO RouOns
  def botOnline(roomId: RoomId, color: Color, online: Boolean): Unit =
    rounds.compute(roomId, (_, cur) => {
      Option(cur).getOrElse(RoundState()).botOnline(color, online) match {
        case None => cur
        case Some(round) =>
          publish(roomId, round)
          round
      }
    })

  def getUsers(roomId: RoomId): Set[User.ID] =
    Option(rounds get roomId).fold(Set.empty[User.ID])(_.room.users.keySet)

  def isPresent(roomId: RoomId, userId: User.ID): Boolean =
    Option(rounds get roomId).exists(_.room.users contains userId)

  private def publish(roomId: RoomId, round: RoundState): Unit =
    json.round(outputOf(roomId, round)) foreach {
      Bus.publish(_, _ room roomId)
    }

  def size = rounds.size
}

object RoundCrowd {

  case class Output(room: RoomCrowd.Output, players: Color.Map[Int]) {
    def isEmpty = room.members == 0 && players.white == 0 && players.black == 0
  }

  def outputOf(roomId: RoomId, round: RoundState) = Output(
    room = RoomCrowd.outputOf(roomId, round.room),
    players = round.players
  )

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
    def botOnline(color: Color, online: Boolean): Option[RoundState] =
      if (online == players(color) > 0) None
      else if (online) Some(connect(None, Some(color)))
      else Some(disconnect(None, Some(color)))
    def isEmpty = room.isEmpty && players.forall(1 > _)
  }
}
