package lila.ws

import akka.actor.typed.scaladsl.ActorContext

import ipc._

object RoomActor {

  import ClientActor._

  case class State(
      id: RoomId,
      isTroll: IsTroll,
      lastCrowd: ClientIn.Crowd = ClientIn.emptyCrowd
  )

  def onStart(
      state: State,
      fromVersion: Option[SocketVersion],
      deps: Deps,
      ctx: ActorContext[ClientMsg]
  ): Unit = {
    import deps._
    ClientActor.onStart(deps, ctx)
    req.user foreach { users.connect(_, ctx.self) }
    Bus.subscribe(Bus.channel room state.id, ctx.self)
    roomCrowd.connect(state.id, req.user)
    History.room.getFrom(state.id, fromVersion) match {
      case None         => clientIn(ClientIn.Resync)
      case Some(events) => events map { versionFor(state.isTroll, _) } foreach clientIn
    }
  }

  def onStop(state: State, deps: Deps, ctx: ActorContext[ClientMsg]): Unit = {
    Bus.unsubscribe(Bus.channel room state.id, ctx.self)
    deps.roomCrowd.disconnect(state.id, deps.req.user)
  }

  def versionFor(isTroll: IsTroll, msg: ClientIn.Versioned): ClientIn.Payload =
    if (!msg.troll.value || isTroll.value) msg.full
    else msg.skip

  def receive(
      state: State,
      deps: Deps
  ): PartialFunction[ClientMsg, (Option[State], Option[LilaIn.AnyRoom])] = {

    case versioned: ClientIn.Versioned =>
      deps.clientIn(versionFor(state.isTroll, versioned))
      None -> None

    case ClientIn.OnlyFor(endpoint, payload) =>
      if (endpoint == ClientIn.OnlyFor.Room(state.id)) deps.clientIn(payload)
      None -> None

    case crowd: ClientIn.Crowd =>
      if (crowd == state.lastCrowd) None -> None
      else
        Some {
          deps.clientIn(crowd)
          state.copy(lastCrowd = crowd)
        } -> None

    case SetTroll(v) =>
      Some(state.copy(isTroll = v)) -> None

    case ClientOut.ChatSay(msg) =>
      None -> deps.req.user.map { u => LilaIn.ChatSay(state.id, u.id, msg) }

    case ClientOut.ChatTimeout(suspect, reason, text) =>
      None -> deps.req.user.map { u => LilaIn.ChatTimeout(state.id, u.id, suspect, reason, text) }
  }
}
