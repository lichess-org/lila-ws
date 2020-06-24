package lila.ws

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import kamon.Kamon
import kamon.tag.TagSet
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

final class Monitor(
    config: Config,
    services: Services
)(implicit
    scheduler: akka.actor.typed.Scheduler,
    ec: ExecutionContext
) {

  import Monitor._

  def start(): Unit = {

    val version  = System.getProperty("java.version")
    val memory   = Runtime.getRuntime().maxMemory() / 1024 / 1024
    val useEpoll = config.getBoolean("netty.useEpoll")
    val useKamon = config.getString("kamon.influxdb.hostname").nonEmpty

    logger.info(s"lila-ws netty epoll=$useEpoll kamon=$useKamon")
    logger.info(s"Java version: $version, memory: ${memory}MB")

    if (useKamon) kamon.Kamon.loadModules()

    scheduler.scheduleWithFixedDelay(5.seconds, 1949.millis) { () => periodicMetrics }
  }

  private def periodicMetrics = {

    val members = LilaWsServer.connections.get
    val rounds  = services.roundCrowd.size
    services.lobby.pong.update(members, rounds)

    connection.current update members
    historyRoomSize.update(History.room.size)
    historyRoundSize.update(History.round.size)
    crowdRoomSize.update(services.roomCrowd.size)
    crowdRoundSize.update(rounds)
    usersSize.update(services.users.size)
    watchSize.update(Fens.size)
    busSize.update(Bus.size)
    busAllSize.update(Bus.sizeOf(_.all))
  }
}

object Monitor {

  private val logger = Logger(getClass)

  object connection {
    val current = Kamon.gauge("connection.current").withoutTags
    def open(endpoint: String) =
      Kamon.counter("connection.open").withTag("endpoint", endpoint).increment()
  }

  def clientOutWrongHole  = Kamon.counter("client.out.wrongHole").withoutTags
  def clientOutUnexpected = Kamon.counter("client.out.unexpected").withoutTags
  def clientOutUnhandled(name: String) =
    Kamon
      .counter("client.out.unhandled")
      .withTag("name", name)
      .increment()

  val historyRoomSize  = Kamon.gauge("history.room.size").withoutTags
  val historyRoundSize = Kamon.gauge("history.round.size").withoutTags

  val crowdRoomSize  = Kamon.gauge("crowd.room.size").withoutTags
  val crowdRoundSize = Kamon.gauge("crowd.round.size").withoutTags
  val usersSize      = Kamon.gauge("users.size").withoutTags
  val watchSize      = Kamon.gauge("watch.size").withoutTags

  val palantirChannels = Kamon.gauge("palantir.channels.size").withoutTags

  val busSize    = Kamon.gauge("bus.size").withoutTags
  val busAllSize = Kamon.gauge("bus.all.size").withoutTags

  val chessMoveTime = Kamon.timer("chess.analysis.move.time").withoutTags
  val chessDestTime = Kamon.timer("chess.analysis.dest.time").withoutTags

  def websocketError(name: String) =
    Kamon.counter("websocket.error").withTag("name", name).increment()

  def rateLimit(name: String) =
    Kamon
      .counter("ratelimit")
      .withTag("name", name)
      .increment()

  object redis {
    val publishTime      = Kamon.timer("redis.publish.time").withoutTags
    private val countIn  = Kamon.counter("redis.in")
    private val countOut = Kamon.counter("redis.out")
    def in(chan: String, path: String) =
      countIn
        .withTags(TagSet.from(Map("channel" -> chan, "path" -> path)))
        .increment()
    def out(chan: String, path: String) =
      countOut
        .withTags(TagSet.from(Map("channel" -> chan, "path" -> path)))
        .increment()
    def queue(chan: String, path: String) =
      Kamon
        .counter("redis.out.queue")
        .withTags(TagSet.from(Map("channel" -> chan, "path" -> path)))
        .increment()
    def drop(chan: String, path: String) =
      Kamon
        .counter("redis.out.drop")
        .withTags(TagSet.from(Map("channel" -> chan, "path" -> path)))
        .increment()
  }

  def time[A](metric: Monitor.type => kamon.metric.Timer)(f: => A): A = {
    val timer = metric(Monitor).start()
    val res   = f
    timer.stop
    res
  }
}
