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
    import deps._
    onStart(deps, ctx)
    req.user foreach { u =>
      queue(_.user, UserSM.Connect(u, ctx.self))
    }
    apply(State(), deps)
  }

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] = Behaviors.receive[ClientMsg] { (ctx, msg) =>

    msg match {

      case ctrl: ClientCtrl => ClientActor.socketControl(state, deps.req.flag, ctrl)

      case ClientIn.OnlyFor(endpoint, payload) =>
        if (endpoint == ClientIn.OnlyFor.Site) deps.clientIn(payload)
        Behaviors.same

      case in: ClientIn => clientInReceive(state, deps, in) match {
        case None => Behaviors.same
        case Some(s) => apply(s, deps)
      }

      case msg: ClientOutSite =>
        val newState = globalReceive(state, deps, ctx, msg)
        if (newState == state) Behaviors.same
        else apply(newState, deps)

      case msg =>
        Monitor.clientOutUnexpected.increment()
        if (state.ignoreLog) Behaviors.same
        else {
          Logger("SiteClientActor").info(s"Wrong $msg ${deps.req}")
          apply(state.copy(ignoreLog = true), deps)
        }
    }

  }.receiveSignal {
    case (ctx, PostStop) =>
      onStop(state, deps, ctx)
      Behaviors.same
  }
}
