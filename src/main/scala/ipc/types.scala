package lila.ws
package ipc

import chess.MoveMetrics
import chess.Centis

trait ClientMsg

sealed trait ClientCtrl extends ClientMsg

object ClientCtrl:
  case object Disconnect            extends ClientCtrl
  case object ApiDisconnect         extends ClientCtrl
  case class Broom(oldSeconds: Int) extends ClientCtrl

object ClientNull               extends ClientMsg
case class SetTroll(v: IsTroll) extends ClientMsg

case class ClientMoveMetrics(
    clientLag: Option[Centis] = None,
    clientMoveTime: Option[Centis] = None
):
  def withFrameLag(frameLag: Option[Centis]) = MoveMetrics(clientLag, clientMoveTime, frameLag)
