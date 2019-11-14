package lila.ws

import akka.actor.typed.scaladsl.ActorContext
import akka.stream.scaladsl.SourceQueue

import ipc._
import sm._

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
    req.user foreach { u =>
      queue(_.user, UserSM.Connect(u, ctx.self))
    }
    bus.on(ctx.self, Bus.channel room state.id)
    queue(_.crowd, RoomCrowd.Connect(state.id, req.user))
    History.room.getFrom(state.id, fromVersion) match {
      case None => clientIn(ClientIn.Resync)
      case Some(events) => events map { versionFor(state.isTroll, _) } foreach clientIn
    }
  }

  def onStop(state: State, deps: Deps, ctx: ActorContext[ClientMsg]): Unit = {
    deps.bus.off(ctx.self, Bus.channel room state.id)
    deps.queue(_.crowd, RoomCrowd.Disconnect(state.id, deps.req.user))
  }

  def versionFor(isTroll: IsTroll, msg: ClientIn.Versioned): ClientIn.Payload =
    if (!msg.troll.value || isTroll.value) msg.full
    else msg.skip

  def receive(state: State, deps: Deps): PartialFunction[ClientMsg, (Option[State], Option[LilaIn.AnyRoom])] = {

    case versioned: ClientIn.Versioned =>
      deps.clientIn(versionFor(state.isTroll, versioned))
      None -> None

    case ClientIn.OnlyFor(endpoint, payload) =>
      if (endpoint == ClientIn.OnlyFor.Room(state.id)) deps.clientIn(payload)
      None -> None

    case crowd: ClientIn.Crowd =>
      if (crowd == state.lastCrowd) None -> None
      else Some {
        deps.clientIn(crowd)
        state.copy(lastCrowd = crowd)
      } -> None

    case SetTroll(v) =>
      Some(state.copy(isTroll = v)) -> None

    case ClientOut.ChatSay(msg) =>
      None -> deps.req.user.map { u =>
        LilaIn.ChatSay(state.id, u.id, msg)
      }

    case ClientOut.ChatTimeout(suspect, reason) =>
      None -> deps.req.user.map { u =>
        LilaIn.ChatTimeout(state.id, u.id, suspect, reason)
      }
  }
}
