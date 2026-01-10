package lila.ws

import cats.syntax.option.*
import ipc.ClientIn.Announce as AnnounceMsg

object AnnounceApi:

  private var current = none[AnnounceMsg]

  def onConnect(deps: ClientActor.Deps): Unit =
    for
      announce <- current
      if deps.req.isLichessMobile
    do deps.clientIn(announce)

  def set(s: Option[JsonString]): Unit =
    current = s.map(AnnounceMsg.apply)
