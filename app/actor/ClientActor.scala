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
    user foreach { u =>
      queue(_.user, UserSM.Connect(u, ctx.self))
    }
    flag foreach { f =>
      bus.subscribe(ctx.self, _ flag f.value)
    }
  }

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
