package lila.ws
package ipc

trait ClientMsg

sealed trait ClientCtrl extends ClientMsg

object ClientCtrl {
  case object Disconnect            extends ClientCtrl
  case object ApiDisconnect         extends ClientCtrl
  case class Broom(oldSeconds: Int) extends ClientCtrl
}

object ClientNull               extends ClientMsg
case class SetTroll(v: IsTroll) extends ClientMsg
