package lila.ws

import akka.actor._
import io.lettuce.core._
import play.api.Logger

import ipc._

final class LilaActor(
    redisUri: RedisURI,
    chanIn: LilaActor.Chan,
    chanOut: LilaActor.Chan,
    onReceive: Actor.Receive
) extends Actor {

  import LilaActor._

  val logger = Logger(getClass)
  val redis = RedisClient create redisUri
  val connIn = redis.connectPubSub()
  val connOut = redis.connectPubSub()

  override def preStart() = {
    connOut.addListener(new pubsub.RedisPubSubAdapter[String, String] {
      override def message(channel: String, message: String): Unit =
        LilaOut read message match {
          case Some(out) => self ! out
          case None => logger.warn(s"Unhandled LilaOut: $message")
        }
    })
    connOut.async.subscribe(chanOut)
  }

  val receive = onReceive orElse {
    case lilaIn: LilaIn => connIn.async.publish(chanIn, lilaIn.write)
  }

  override def postStop() = {
    connIn.close()
    connOut.close()
    redis.shutdown()
  }
}

object LilaActor {

  type Chan = String
}
