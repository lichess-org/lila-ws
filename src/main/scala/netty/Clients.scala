package lila.ws

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import scala.concurrent.{ Future, Promise }

private object Clients {

  type ChannelId = String

  sealed trait Control
  final case class Start(behavior: ClientBehavior, channelId: ChannelId, promise: Promise[Client]) extends Control
  final case class Stop(client: Client) extends Control

  def start: Behavior[Control] = Behaviors.setup { ctx =>
    apply
  }

  def apply: Behavior[Control] =
    Behaviors.receive[Control] { (ctx, msg) =>
      msg match {
        case Start(behavior, channelId, promise) =>
          promise success ctx.spawn(behavior, channelId)
          Behaviors.same
        case Stop(client) =>
          ctx.stop(client)
          Behaviors.same
      }
    }
}
