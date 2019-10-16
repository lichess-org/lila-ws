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
      site: ClientActor.State = ClientActor.State()
  )

  def start(simul: Simul, isTroll: IsTroll, fromVersion: Option[SocketVersion])(deps: Deps): Behavior[ClientMsg] = Behaviors.setup { ctx =>
    import deps._
    val roomId = RoomId(simul.id)
    onStart(deps, ctx)
    user foreach { u =>
      queue(_.user, UserSM.Connect(u, ctx.self))
    }
    bus.subscribe(ctx.self, _ room roomId)
    RoomEvents.getFrom(roomId, fromVersion) match {
      case None => clientIn(ClientIn.Resync)
      case Some(events) => events map { versionFor(isTroll, _) } foreach clientIn
    }
    apply(State(simul, isTroll), deps)
  }

  private def versionFor(isTroll: IsTroll, msg: ClientIn.Versioned): ClientIn.Payload =
    if (!msg.troll.value || isTroll.value) msg.full
    else msg.skip

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] = Behaviors.receive[ClientMsg] { (ctx, msg) =>

    import deps._

    def forward(payload: JsValue): Unit = queue(_.lobby, LilaIn.TellSri(sri, user.map(_.id), payload))

    msg match {

      case ctrl: ClientCtrl => ClientActor.socketControl(state.site, deps.flag, ctrl)

      case versioned: ClientIn.Versioned =>
        clientIn(versionFor(state.isTroll, versioned))
        Behavior.same

      case in: ClientIn =>
        clientIn(in)
        Behavior.same

      case ClientOut.ChatSay(msg) =>
        user foreach { u =>
          queue(_.simul, LilaIn.ChatSay(state.simul.id, u.id, msg))
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
      Behaviors.same
  }
}
