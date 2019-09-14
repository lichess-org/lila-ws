package lila.ws

import akka.stream.scaladsl._
import io.lettuce.core._
import io.lettuce.core.pubsub._
import org.slf4j.LoggerFactory

import ipc._

final class Redis {

  private val redisUri = RedisURI.create(Configuration.redisUri)

  private val logger = LoggerFactory.getLogger(getClass)
  private val redis = RedisClient create redisUri

  def pubsub[Out](chanIn: String, chanOut: String)(collect: PartialFunction[LilaOut, Out]) = {

    val connIn = redis.connectPubSub()
    val connOut = redis.connectPubSub()

    def send(in: LilaIn): Unit = connIn.async.publish(chanIn, in.write)

    val init: (SourceQueueWithComplete[Out], List[LilaIn]) => Unit = (queue, initialMsgs) => {

      initialMsgs foreach send

      connOut.async.subscribe(chanOut)

      connOut.addListener(new RedisPubSubAdapter[String, String] {
        override def message(channel: String, msg: String): Unit =
          LilaOut read msg match {
            case Some(out) => collect lift out match {
              case Some(typed) => queue offer typed
              case None => logger.warn(s"Received $out on wrong channel: $chanOut")
            }
            case None => logger.warn(s"Unhandled LilaOut: $msg")
          }
      })
    }

    val sink = Sink foreach send

    (init, sink)
  }
}
