package lila.ws

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior, PostStop }
import play.api.libs.json._
import play.api.Logger

import ipc._

object SiteClientActor {

  def start(deps: Deps): Behavior[ClientMsg] = Behaviors.setup { ctx =>
    import deps._
    queue(_.count, CountSM.Connect)
    bus.subscribe(ctx.self, _ sri sri)
    bus.subscribe(ctx.self, _.all)
    user foreach { u =>
      queue(_.user, UserSM.Connect(u, ctx.self))
    }
    flag foreach { f =>
      bus.subscribe(ctx.self, _ flag f.value)
    }
    apply(State(), deps)
  }

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] = Behaviors.receive[ClientMsg] { (ctx, msg) =>

    import state._
    import deps._

    msg match {

      case ClientOut.Ping(lag) =>
        clientIn ! ClientIn.Pong
        for { l <- lag; u <- user } queue(_.lag, LagSM.Set(u, l))
        Behavior.same

      case in: ClientIn =>
        clientIn ! in
        Behavior.same

      case ClientOut.Watch(gameIds) =>
        queue(_.fen, FenSM.Watch(gameIds, ctx.self))
        apply(
          state.copy(watchedGames = state.watchedGames ++ gameIds),
          deps
        )

      case ClientOut.MoveLat =>
        bus.subscribe(ctx.self, _.mlat)
        Behavior.same

      case ClientOut.Notified =>
        user foreach { u =>
          queue(_.lila, LilaIn.Notified(u.id))
        }
        Behavior.same

      case ClientOut.FollowingOnline =>
        user foreach { u =>
          queue(_.lila, LilaIn.Friends(u.id))
        }
        Behavior.same

      case opening: ClientOut.Opening =>
        Chess(opening) foreach clientIn.!
        Behavior.same

      case anaMove: ClientOut.AnaMove =>
        clientIn ! Chess(anaMove)
        Behavior.same

      case anaDrop: ClientOut.AnaDrop =>
        clientIn ! Chess(anaDrop)
        Behavior.same

      case anaDests: ClientOut.AnaDests =>
        clientIn ! Chess(anaDests)
        Behavior.same

      case ClientOut.Forward(payload) =>
        queue(_.lila, LilaIn.TellSri(sri, user.map(_.id), payload))
        Behavior.same

      case ClientOut.Unexpected(msg) =>
        if (state.ignoreLog) Behavior.same
        else {
          Logger("SiteClient").warn(s"Unexpected $msg UA:$userAgent IP:$ipAddress")
          apply(state.copy(ignoreLog = true), deps)
        }

      case ClientCtrl.Disconnect =>
        Behaviors.stopped
    }
  }.receiveSignal {
    case (ctx, PostStop) =>
      import deps._
      queue(_.count, CountSM.Disconnect)
      user foreach { u =>
        queue(_.user, UserSM.Disconnect(u, ctx.self))
      }
      if (state.watchedGames.nonEmpty) queue(_.fen, FenSM.Unwatch(state.watchedGames, ctx.self))
      bus unsubscribe ctx.self
      Behaviors.same
  }

  case class Deps(
      clientIn: ActorRef[ClientIn],
      queue: Stream.Queues,
      sri: Sri,
      flag: Option[Flag],
      user: Option[User],
      userAgent: String,
      ipAddress: String,
      bus: Bus
  )

  case class State(
      watchedGames: Set[Game.ID] = Set.empty,
      ignoreLog: Boolean = false
  )
}
