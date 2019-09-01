package lila.ws

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ Behaviors, ActorContext }
import akka.actor.typed.{ ActorRef, Behavior, PostStop }
import akka.stream.scaladsl.SourceQueueWithComplete
import play.api.libs.json._
import scala.concurrent.Future

import ipc._

object SiteClientActor {

  def empty(deps: Deps): Behavior[ClientMsg] = Behaviors.setup { ctx =>
    import deps._
    queues count CountSM.Connect
    bus.subscribe(ctx.self, _ sri sri)
    bus.subscribe(ctx.self, _.all)
    user foreach { u =>
      queues user UserSM.Connect(u, ctx.self)
    }
    flag foreach { f =>
      bus.subscribe(ctx.self, _ flag f.value)
    }
    apply(Set.empty, deps)
  }

  private def apply(watchedGames: Set[Game.ID], deps: Deps): Behavior[ClientMsg] = Behaviors.receive[ClientMsg] { (ctx, msg) =>

    import deps._

    msg match {

      case ClientFlow.Disconnect =>
        Behaviors.stopped

      case in: ClientIn =>
        clientIn ! in
        Behavior.same

      case ClientOut.Ping(lag) =>
        clientIn ! ClientIn.Pong
        for { l <- lag; u <- user } queues lag LagSM.Set(u, l)
        Behavior.same

      case ClientOut.Watch(gameIds) =>
        queues fen FenSM.Watch(gameIds, ctx.self)
        apply(
          watchedGames ++ gameIds,
          deps
        )

      case ClientOut.MoveLat =>
        bus.subscribe(ctx.self, _.mlat)
        Behavior.same

      case ClientOut.Notified =>
        user foreach { u =>
          queues lila LilaIn.Notified(u.id)
        }
        Behavior.same

      case ClientOut.FollowingOnline =>
        user foreach { u =>
          queues lila LilaIn.Friends(u.id)
        }
        Behavior.same

      case opening: ClientOut.Opening =>
        Chess(opening) foreach clientIn.!
        Behavior.same

      case anaMove: ClientOut.AnaMove =>
        Chess(anaMove) foreach clientIn.!
        Behavior.same

      case anaDrop: ClientOut.AnaDrop =>
        Chess(anaDrop) foreach clientIn.!
        Behavior.same

      case anaDests: ClientOut.AnaDests =>
        clientIn ! Chess(anaDests)
        Behavior.same

      case ClientOut.Forward(payload) =>
        queues lila LilaIn.TellSri(sri, user.map(_.id), payload)
        Behavior.same
    }
  }.receiveSignal {
    case (ctx, PostStop) =>
      import deps._
      queues count CountSM.Disconnect
      user foreach { u =>
        queues user UserSM.Disconnect(u, ctx.self)
      }
      if (watchedGames.nonEmpty) queues fen FenSM.Unwatch(watchedGames, ctx.self)
      bus unsubscribe ctx.self
      Behaviors.same
  }

  case class Deps(
      clientIn: ActorRef[ClientIn],
      queues: Stream.Queues,
      sri: Sri,
      flag: Option[Flag],
      user: Option[User],
      bus: Bus
  )
}
