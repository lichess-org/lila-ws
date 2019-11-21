package lila.ws

package object ipc {

  trait ClientMsg

  sealed trait ClientCtrl extends ClientMsg

  object ClientCtrl {
    case object Disconnect extends ClientCtrl
    case class Broom(oldSeconds: Int) extends ClientCtrl
  }

  object ClientNull extends ClientMsg
  case class RoundUserTvNewGame(userId: User.ID) extends ClientMsg
  case class SetTroll(v: IsTroll) extends ClientMsg
}
