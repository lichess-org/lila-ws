package lila.ws

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ Behaviors, ActorContext }
import akka.actor.typed.{ ActorRef, Behavior, PostStop }
import io.lettuce.core._
import play.api.Logger

import ipc._

object LilaActor {

  private val logger = Logger(getClass)

  type Chan = String

  def start(
    redisUri: RedisURI,
    chanIn: Chan,
    chanOut: Chan,
    onReceive: LilaOut => Unit
  ): Behavior[LilaMsg] = Behaviors.setup[LilaMsg] { ctx =>

    val redis = RedisClient create redisUri
    val connIn = redis.connectPubSub()
    val connOut = redis.connectPubSub()

    ctx.self ! LilaIn.DisconnectAll

    connOut.addListener(new pubsub.RedisPubSubAdapter[String, String] {
      override def message(channel: String, message: String): Unit =
        LilaOut read message match {
          case Some(out) => ctx.self ! out
          case None => logger.warn(s"Unhandled LilaOut: $message")
        }
    })
    connOut.async.subscribe(chanOut)

    Behaviors.receiveMessage[LilaMsg] {

      case in: LilaIn =>
        connIn.async.publish(chanIn, in.write)
        Behavior.same

      case out: LilaOut =>
        onReceive(out)
        Behavior.same

    }.receiveSignal {
      case (ctx, PostStop) =>
        connIn.close()
        connOut.close()
        redis.shutdown()
        Behavior.same
    }
  }
}
