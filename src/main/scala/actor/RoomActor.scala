package lila.ws

import org.apache.pekko.actor.typed.scaladsl.ActorContext

import ipc.*

object RoomActor:

  import ClientActor.*

  case class State(
      room: RoomId,
      isTroll: IsTroll,
      lastCrowd: ClientIn.Crowd = ClientIn.emptyCrowd
  )

  def onStart(
      state: State,
      fromVersion: Option[SocketVersion],
      deps: Deps,
      ctx: ActorContext[ClientMsg]
  ): Unit =
    import deps.*
    ClientActor.onStart(deps, ctx)
    req.user foreach { users.connect(_, ctx.self) }
    Bus.subscribe(Bus.channel room state.room, ctx.self)
    roomCrowd.connect(state.room, req.user)
    History.room.getFrom(state.room, fromVersion) match
      case None         => clientIn(ClientIn.Resync)
      case Some(events) => events map { versionFor(state.isTroll, _) } foreach clientIn

  def onStop(state: State, deps: Deps, ctx: ActorContext[ClientMsg]): Unit =
    Bus.unsubscribe(Bus.channel room state.room, ctx.self)
    deps.roomCrowd.disconnect(state.room, deps.req.user)

  def versionFor(isTroll: IsTroll, msg: ClientIn.Versioned): ClientIn.Payload =
    if msg.troll.no || isTroll.yes then msg.full
    else msg.skip

  def receive(
      state: State,
      deps: Deps
  ): PartialFunction[ClientMsg, (Option[State], Option[LilaIn.AnyRoom])] =

    case versioned: ClientIn.Versioned =>
      deps.clientIn(versionFor(state.isTroll, versioned))
      None -> None

    case ClientIn.OnlyFor(endpoint, payload) =>
      if endpoint == ClientIn.OnlyFor.Endpoint.Room(state.room) then deps.clientIn(payload)
      None -> None

    case crowd: ClientIn.Crowd =>
      if crowd == state.lastCrowd then None -> None
      else
        Some {
          deps.clientIn(crowd)
          state.copy(lastCrowd = crowd)
        } -> None

    case SetTroll(v) =>
      Some(state.copy(isTroll = v)) -> None

    case ClientOut.ChatSay(msg) =>
      None -> deps.req.user.map { LilaIn.ChatSay(state.room, _, msg) }

    case ClientOut.ChatTimeout(suspect, reason, text) =>
      None -> deps.req.user.map { LilaIn.ChatTimeout(state.room, _, suspect, reason, text) }
