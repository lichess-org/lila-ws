package lila.ws

package object ipc {

  trait ClientMsg

  sealed trait ClientCtrl extends ClientMsg

  object ClientCtrl {
    case object Disconnect extends ClientCtrl
    case class Broom(oldSeconds: Int) extends ClientCtrl
  }

  object ClientNull extends ClientMsg
  case class UserTvNewGame(userId: User.ID) extends ClientMsg
  case class RoundBotOnline(gameId: Game.Id, color: chess.Color, online: Boolean) extends ClientMsg
  case class SetTroll(v: IsTroll) extends ClientMsg
}
