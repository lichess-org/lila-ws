package lila.ws

import akka.actor.typed.scaladsl.{ Behaviors, ActorContext }
import akka.actor.typed.{ ActorRef, Behavior, PostStop }
import play.api.libs.json._
import play.api.Logger

import ipc._
import sm._

object LobbyClientActor {

  import ClientActor._

  def start(deps: Deps): Behavior[ClientMsg] = Behaviors.setup { ctx =>
    import deps._
    onStart(deps, ctx)
    deps.user foreach { u =>
      deps.queue(_.user, UserSM.ConnectSilently(u, ctx.self))
    }
    queue(_.lobby, LilaIn.ConnectSri(sri, user.map(_.id)))
    bus.subscribe(ctx.self, _.lobby)
    apply(State(), deps)
  }

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] = Behaviors.receive[ClientMsg] { (ctx, msg) =>

    import deps._

    msg match {

      case in: ClientIn =>
        clientIn(in)
        Behavior.same

      case ClientCtrl.Disconnect =>
        Behavior.stopped

      case ClientOut.Ping(lag) =>
        clientIn(LobbyPongStore.get)
        for { l <- lag; u <- user } queue(_.lag, LagSM.Set(u, l))
        Behavior.same

      case ClientOut.Forward(payload) =>
        queue(_.lobby, LilaIn.TellSri(sri, user.map(_.id), payload))
        Behavior.same

      // default receive (site)
      case msg: ClientOutSite =>
        val newState = globalReceive(state, deps, ctx, msg)
        if (newState == state) Behavior.same
        else apply(newState, deps)
    }

  }.receiveSignal {
    case (ctx, PostStop) =>
      import deps._
      onStop(state, deps, ctx)
      queue(_.lobby, LilaIn.DisconnectSri(sri))
      Behaviors.same
  }
}
