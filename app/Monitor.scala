package lila.ws

import javax.inject._
import kamon.Kamon
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

@Singleton
final class Monitor @Inject() (config: play.api.Configuration)(implicit system: akka.actor.ActorSystem, ec: ExecutionContext) {

  import Monitor._

  def start: Unit = {

    if (config.get[String]("kamon.influxdb.hostname").nonEmpty) {
      play.api.Logger(getClass).info("Kamon is enabled")
      kamon.Kamon.loadModules()
    }

    system.scheduler.scheduleWithFixedDelay(5.seconds, 1949.millis) { () => periodicMetrics }
  }

  private def periodicMetrics = {
    historyRoomSize.update(History.room.size)
    historyRoundSize.update(History.round.size)
  }
}

object Monitor {

  val redisPublishTime = Kamon.timer("redis.publish.time").withoutTags
  val clientOutUnexpected = Kamon.counter("client.out.unexpected").withoutTags

  val historyRoomSize = Kamon.gauge("history.room.size").withoutTags
  val historyRoundSize = Kamon.gauge("history.round.size").withoutTags
}
