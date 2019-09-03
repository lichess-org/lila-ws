package lila.ws

import akka.actor.typed.scaladsl.{ Behaviors, ActorContext }
import akka.actor.typed.{ ActorRef, Behavior, PostStop }
import akka.stream.scaladsl._

import ipc._

object ClientActor {

  def onStart(deps: Deps, ctx: ActorContext[ClientMsg]): Unit = {
    import deps._
    queue(_.count, CountSM.Connect)
    bus.subscribe(ctx.self, _ sri sri)
    bus.subscribe(ctx.self, _.all)
    flag foreach { f =>
      bus.subscribe(ctx.self, _ flag f.value)
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

  case class Deps(
      client: SourceQueue[ClientIn],
      queue: Stream.Queues,
      sri: Sri,
      flag: Option[Flag],
      user: Option[User],
      userAgent: String,
      ipAddress: String,
      bus: Bus
  ) {
    def clientIn(msg: ClientIn): Unit = client offer msg
  }
}
