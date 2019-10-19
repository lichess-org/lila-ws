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
    roomState: RoomActor.State,
    fromVersion: Option[SocketVersion],
    deps: Deps,
    ctx: ActorContext[ClientMsg]
  ): Unit = {
    import deps._
    ClientActor.onStart(deps, ctx)
    req.user foreach { u =>
      queue(_.user, UserSM.Connect(u, ctx.self))
    }
    bus.subscribe(ctx.self, _ room roomState.id)
    queue(_.crowd, RoomCrowd.Connect(roomState.id, req.user))
    RoomEvents.getFrom(roomState.id, fromVersion) match {
      case None => clientIn(ClientIn.Resync)
      case Some(events) => events map { versionFor(roomState.isTroll, _) } foreach clientIn
    }
  }

  def onStop(state: State, deps: Deps): Unit =
    deps.queue(_.crowd, RoomCrowd.Disconnect(state.id, deps.req.user))

  def versionFor(isTroll: IsTroll, msg: ClientIn.Versioned): ClientIn.Payload =
    if (!msg.troll.value || isTroll.value) msg.full
    else msg.skip

  def receive(state: State, deps: Deps, queue: SourceQueue[LilaIn.Room]): PartialFunction[ClientMsg, Option[State]] = {

    case versioned: ClientIn.Versioned =>
      deps.clientIn(versionFor(state.isTroll, versioned))
      None

    case ClientIn.OnlyFor(endpoint, payload) =>
      if (endpoint == ClientIn.OnlyFor.Room(state.id)) deps.clientIn(payload)
      None

    case crowd: ClientIn.Crowd =>
      if (crowd == state.lastCrowd) None
      else Some {
        deps.clientIn(crowd)
        state.copy(lastCrowd = crowd)
      }

    case in: ClientIn =>
      deps.clientIn(in)
      None

    case ClientOut.ChatSay(msg) =>
      deps.req.user foreach { u =>
        queue offer (LilaIn.ChatSay(state.id, u.id, msg): LilaIn.Room)
      }
      None

    case ClientOut.ChatTimeout(suspect, reason) =>
      deps.req.user foreach { u =>
        queue offer LilaIn.ChatTimeout(state.id, u.id, suspect, reason)
      }
      None
  }
}
