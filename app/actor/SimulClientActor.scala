package lila.ws

import akka.actor.typed.scaladsl.{ Behaviors, ActorContext }
import akka.actor.typed.{ ActorRef, Behavior, PostStop }
import play.api.libs.json._

import ipc._
import sm._

object SimulClientActor {

  import ClientActor._

  case class State(
      simul: Simul,
      isTroll: IsTroll,
      lastCrowd: ClientIn.Crowd = ClientIn.emptyCrowd,
      site: ClientActor.State = ClientActor.State()
  ) {
    def roomId = RoomId(simul.id)
  }

  def start(simul: Simul, isTroll: IsTroll, fromVersion: Option[SocketVersion])(deps: Deps): Behavior[ClientMsg] = Behaviors.setup { ctx =>
    import deps._
    val state = State(simul, isTroll)
    onStart(deps, ctx)
    req.user foreach { u =>
      queue(_.user, UserSM.Connect(u, ctx.self))
    }
    queue(_.crowd, CrowdSM.Connect(state.roomId, req.user))
    bus.subscribe(ctx.self, _ room state.roomId)
    RoomEvents.getFrom(state.roomId, fromVersion) match {
      case None => clientIn(ClientIn.Resync)
      case Some(events) => events map { versionFor(isTroll, _) } foreach clientIn
    }
    apply(state, deps)
  }

  private def versionFor(isTroll: IsTroll, msg: ClientIn.Versioned): ClientIn.Payload =
    if (!msg.troll.value || isTroll.value) msg.full
    else msg.skip

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] = Behaviors.receive[ClientMsg] { (ctx, msg) =>

    import deps._

    msg match {

      case ctrl: ClientCtrl => ClientActor.socketControl(state.site, deps.req.flag, ctrl)

      case versioned: ClientIn.Versioned =>
        clientIn(versionFor(state.isTroll, versioned))
        Behavior.same

      case ClientIn.OnlyFor(endpoint, payload) =>
        if (endpoint == ClientIn.OnlyFor.Room(state.simul.id)) clientIn(payload)
        Behavior.same

      case crowd: ClientIn.Crowd =>
        if (crowd == state.lastCrowd) Behavior.same
        else {
          clientIn(crowd)
          apply(state.copy(lastCrowd = crowd), deps)
        }

      case in: ClientIn =>
        clientIn(in)
        Behavior.same

      case ClientOut.ChatSay(msg) =>
        req.user foreach { u =>
          queue(_.simul, LilaIn.ChatSay(state.simul.id, u.id, msg))
        }
        Behavior.same

      case ClientOut.ChatTimeout(suspect, reason) =>
        req.user foreach { u =>
          queue(_.simul, LilaIn.ChatTimeout(state.simul.id, u.id, suspect, reason))
        }
        Behavior.same

      // default receive (site)
      case msg: ClientOutSite =>
        val siteState = globalReceive(state.site, deps, ctx, msg)
        if (siteState == state.site) Behavior.same
        else apply(state.copy(site = siteState), deps)
    }

  }.receiveSignal {
    case (ctx, PostStop) =>
      onStop(state.site, deps, ctx)
      deps.queue(_.crowd, CrowdSM.Disconnect(state.roomId, deps.req.user))
      Behaviors.same
  }
}
