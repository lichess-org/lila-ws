package lila.ws

import akka.actor.typed.scaladsl.{ Behaviors, ActorContext }
import akka.actor.typed.{ ActorRef, Behavior, PostStop }
import play.api.libs.json._
import play.api.Logger

import ipc._

object SiteClientActor {

  import ClientActor._

  def start(deps: Deps): Behavior[ClientMsg] = Behaviors.setup { ctx =>
    onStart(deps, ctx)
    apply(State(), deps)
  }

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] = Behaviors.receive[ClientMsg] { (ctx, msg) =>

    msg match {

      case in: ClientIn =>
        deps.clientIn(in)
        Behavior.same

      case ClientCtrl.Disconnect =>
        Behaviors.stopped

      case msg: ClientOut =>
        val newState = receive(state, deps, ctx, msg)
        if (newState == state) Behavior.same
        else apply(newState, deps)
    }

  }.receiveSignal {
    case (ctx, PostStop) =>
      onStop(state, deps, ctx)
      Behaviors.same
  }

  def receive(state: State, deps: Deps, ctx: ActorContext[ClientMsg], msg: ClientOut): State = {

    import state._
    import deps._

    msg match {

      case ClientOut.Ping(lag) =>
        clientIn(ClientIn.Pong)
        for { l <- lag; u <- user } queue(_.lag, LagSM.Set(u, l))
        state

      case ClientOut.Watch(gameIds) =>
        queue(_.fen, FenSM.Watch(gameIds, ctx.self))
        state.copy(watchedGames = state.watchedGames ++ gameIds)

      case ClientOut.MoveLat =>
        bus.subscribe(ctx.self, _.mlat)
        state

      case ClientOut.Notified =>
        user foreach { u =>
          queue(_.site, LilaIn.Notified(u.id))
        }
        state

      case ClientOut.FollowingOnline =>
        user foreach { u =>
          queue(_.site, LilaIn.Friends(u.id))
        }
        state

      case opening: ClientOut.Opening =>
        Chess(opening) foreach clientIn
        state

      case anaMove: ClientOut.AnaMove =>
        clientIn(Chess(anaMove))
        state

      case anaDrop: ClientOut.AnaDrop =>
        clientIn(Chess(anaDrop))
        state

      case anaDests: ClientOut.AnaDests =>
        clientIn(Chess(anaDests))
        state

      case ClientOut.Forward(payload) =>
        queue(_.site, LilaIn.TellSri(sri, user.map(_.id), payload))
        state

      case ClientOut.Unexpected(msg) =>
        if (state.ignoreLog) state
        else {
          Logger("SiteClient").info(s"Unexpected $msg IP: $ipAddress UA: $userAgent")
          state.copy(ignoreLog = true)
        }

      case ClientOut.Ignore =>
        state
    }
  }

  def onStop(state: State, deps: Deps, ctx: ActorContext[ClientMsg]): Unit = {
    import deps._
    queue(_.count, CountSM.Disconnect)
    user foreach { u =>
      queue(_.user, UserSM.Disconnect(u, ctx.self))
    }
    if (state.watchedGames.nonEmpty) queue(_.fen, FenSM.Unwatch(state.watchedGames, ctx.self))
    bus unsubscribe ctx.self
  }

  case class State(
      watchedGames: Set[Game.ID] = Set.empty,
      ignoreLog: Boolean = false
  )
}
