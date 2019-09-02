package lila.ws

import akka.stream.scaladsl._
import io.lettuce.core._
import play.api.Logger

import ipc._

final class Lila(
    redisUri: RedisURI,
    chanIn: String,
    chanOut: String
) {

  private val logger = Logger(getClass)
  private val redis = RedisClient create redisUri
  private val connIn = redis.connectPubSub()
  private val connOut = redis.connectPubSub()

  connOut.async.subscribe(chanOut)

  def plugSource(queue: SourceQueueWithComplete[LilaOut]) =
    connOut.addListener(new pubsub.RedisPubSubAdapter[String, String] {
      override def message(channel: String, message: String): Unit =
        LilaOut read message match {
          case Some(out) => queue offer out
          case None => logger.warn(s"Unhandled LilaOut: $message")
        }
    })

  def sink: Sink[LilaIn, _] = Sink foreach send

  def send(in: LilaIn): Unit = connIn.async.publish(chanIn, in.write)
}
