package lila.ws

import akka.actor.typed.scaladsl.{ Behaviors, ActorContext }
import akka.actor.typed.{ ActorRef, Behavior, PostStop }
import play.api.libs.json._
import play.api.Logger

import ipc._
import sm._

object SimulClientActor {

  import ClientActor._

  case class State(
      simul: Simul,
      site: ClientActor.State = ClientActor.State()
  )

  def start(simul: Simul)(deps: Deps): Behavior[ClientMsg] = Behaviors.setup { ctx =>
    import deps._
    onStart(deps, ctx)
    deps.user foreach { u =>
      deps.queue(_.user, UserSM.Connect(u, ctx.self))
    }
    deps.user foreach { u =>
      deps.queue(_.simulState, SimulSM.Connect(simul, u))
    }
    bus.subscribe(ctx.self, _ chat simul.id)
    apply(State(simul), deps)
  }

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] = Behaviors.receive[ClientMsg] { (ctx, msg) =>

    import deps._

    def forward(payload: JsValue): Unit = queue(_.lobby, LilaIn.TellSri(sri, user.map(_.id), payload))

    msg match {

      case ctrl: ClientCtrl => ClientActor.socketControl(state.site, deps.flag, ctrl)

      //       case ClientIn.OnlyFor(endpoint, payload) =>
      //         if (endpoint == ClientIn.OnlyFor.Simul) clientIn(payload)
      //         Behavior.same

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
      deps.user foreach { u =>
        deps.queue(_.simulState, SimulSM.Disconnect(simul, u))
      }
      Behaviors.same
  }
}
