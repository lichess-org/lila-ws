package lila.ws

import akka.stream.scaladsl._
import io.lettuce.core._
import io.lettuce.core.pubsub._
import play.api.Logger

import ipc._

final class Lila(redisUri: RedisURI) {

  private val logger = Logger(getClass)
  private val redis = RedisClient create redisUri

  private var closeFunctions = List.empty[() => Unit]

  def pubsub[Out](chanIn: String, chanOut: String)(collect: PartialFunction[LilaOut, Out]) = {

    val connIn = redis.connectPubSub()
    val connOut = redis.connectPubSub()

    def send(in: LilaIn): Unit = {
      val timer = Monitor.redisPublishTime.start()
      connIn.async.publish(chanIn, in.write).thenRun { timer.stop _ }
    }

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
            case None => logger.warn(s"Unhandled $channel LilaOut: $msg")
          }
      })
    }

    val sink = Sink foreach send

    val close = () => {
      connIn.close()
      connOut.close()
    }
    closeFunctions = close :: closeFunctions

    (init, sink)
  }

  def closeAll: Unit = {
    closeFunctions.foreach(_())
    closeFunctions = Nil
  }
}
