package lila.ws
package ipc

import chess.{ Centis, MoveMetrics }

trait ClientMsg

sealed trait ClientCtrl extends ClientMsg

object ClientCtrl:
  case class Disconnect(reason: String) extends ClientCtrl
  case object ApiDisconnect extends ClientCtrl
  case class Broom(oldSeconds: Int) extends ClientCtrl

object ClientNull extends ClientMsg
case class SetTroll(v: IsTroll) extends ClientMsg

case class ClientMoveMetrics(
    clientLag: Option[Centis] = None,
    clientMoveTime: Option[Centis] = None
):
  def withFrameLag(frameLag: Option[Centis]) = MoveMetrics(clientLag, clientMoveTime, frameLag)
