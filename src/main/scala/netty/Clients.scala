package lila.ws

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import scala.concurrent.{ Future, Promise }

object Clients {

  sealed trait Control
  final case class Start(behavior: ClientBehavior, promise: Promise[Client]) extends Control
  final case class Stop(client: Client) extends Control

  def start: Behavior[Control] = Behaviors.setup { ctx =>
    apply
  }

  def apply: Behavior[Control] =
    Behaviors.receive[Control] { (ctx, msg) =>
      msg match {
        case Start(behavior, promise) =>
          promise success ctx.spawnAnonymous(behavior)
          Behaviors.same
        case Stop(client) =>
          ctx.stop(client)
          Behaviors.same
      }
    }
}
