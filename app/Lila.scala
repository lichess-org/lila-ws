package lila.ws

import akka.stream.scaladsl._
import io.lettuce.core._
import io.lettuce.core.pubsub._
import play.api.Logger

import ipc._

final class Lila(redisUri: RedisURI) {

  private val logger = Logger(getClass)
  private val redis = RedisClient create redisUri

  def pubsub[Out](chanIn: String, chanOut: String)(collect: PartialFunction[LilaOut, Out]) = {

    val connIn = redis.connectPubSub()
    val connOut = redis.connectPubSub()

    def send(in: LilaIn): Unit = connIn.async.publish(chanIn, in.write)

    val init: (SourceQueueWithComplete[Out], List[LilaIn]) => Unit = (queue, initialMsgs) => {

      initialMsgs foreach send

      connOut.async.subscribe(chanOut)

      connOut.addListener(new RedisPubSubAdapter[String, String] {
        override def message(channel: String, message: String): Unit =
          LilaOut read message match {
            case Some(out) => collect lift out foreach queue.offer
            case None => logger.warn(s"Unhandled LilaOut: $message")
          }
      })
    }

    val sink = Sink foreach send

    (init, sink)
  }
}
