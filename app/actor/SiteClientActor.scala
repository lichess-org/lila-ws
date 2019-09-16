package lila.ws

import akka.actor.typed.scaladsl.{ Behaviors, ActorContext }
import akka.actor.typed.{ ActorRef, Behavior, PostStop }
import play.api.libs.json._
import play.api.Logger

import ipc._
import sm._

object SiteClientActor {

  import ClientActor._

  def start(deps: Deps): Behavior[ClientMsg] = Behaviors.setup { ctx =>
    onStart(deps, ctx)
    deps.user foreach { u =>
      deps.queue(_.user, UserSM.Connect(u, ctx.self))
    }
    apply(State(), deps)
  }

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] = Behaviors.receive[ClientMsg] { (ctx, msg) =>

    msg match {

      case ctrl: ClientCtrl => ClientActor.socketControl(state, deps.flag, ctrl)

      case ClientIn.OnlyFor(endpoint, payload) =>
        if (endpoint == ClientIn.OnlyFor.Site) deps.clientIn(payload)
        Behavior.same

      case in: ClientIn =>
        deps.clientIn(in)
        Behavior.same

      case msg: ClientOutSite =>
        val newState = globalReceive(state, deps, ctx, msg)
        if (newState == state) Behavior.same
        else apply(newState, deps)
    }

  }.receiveSignal {
    case (ctx, PostStop) =>
      onStop(state, deps, ctx)
      Behaviors.same
  }
}
