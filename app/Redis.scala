package lichess.ws

import io.lettuce.core._
import javax.inject._
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import scala.concurrent.{ ExecutionContext, Future }

import ipc.Command

@Singleton
final class Redis @Inject() (
    config: Configuration,
    lifecycle: ApplicationLifecycle
)(implicit executionContext: ExecutionContext) {

  import Redis.Send

  private val uri = config.get[String]("redis.uri")
  private val redisClient = RedisClient create RedisURI.create(uri)

  private val connIn = redisClient.connectPubSub()
  private val connOut = redisClient.connectPubSub()
  private val chanIn = "site-in"
  private val chanOut = "site-out"

  // takes a listener, returns a sender
  def connect(listener: Send): Send = {

    connOut.addListener(new pubsub.RedisPubSubAdapter[String, String] {
      override def message(channel: String, message: String): Unit = {
        listener(Command(message))
      }
    })

    command => connIn.async.publish(chanIn, command.toString)
  }

  connOut.async.subscribe(chanOut)

  lifecycle.addStopHook { () =>
    Future {
      connIn.close()
      connOut.close()
      redisClient.shutdown()
    }
  }
}

object Redis {

  type Send = Command => Unit
}
