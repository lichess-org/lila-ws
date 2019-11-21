package lila.ws

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import io.netty.channel.Channel
import io.netty.util.AttributeKey

object Clients {

  type ChannelId = String

  sealed trait Control
  final case class Start(behavior: ClientBehavior, channel: Channel) extends Control
  final case class Stop(client: Client) extends Control

  def start: Behavior[Control] = Behaviors.setup { ctx =>
    apply
  }

  def apply: Behavior[Control] =
    Behaviors.receive[Control] { (ctx, msg) =>
      msg match {
        case Start(behavior, channel) =>
          val client = ctx.spawn(behavior, channel.id.asShortText)
          channel.attr(attrKey).set(client)
          println(channel.attr(attrKey).get)
          Behaviors.same
        case Stop(client) =>
          ctx.stop(client)
          Behaviors.same
      }
    }

  val attrKey = AttributeKey.valueOf[Client]("client")
}
