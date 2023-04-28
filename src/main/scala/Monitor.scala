package lila.ws

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import kamon.Kamon
import kamon.tag.TagSet
import java.util.concurrent.TimeUnit

final class Monitor(
    config: Config,
    services: Services
)(using
    scheduler: akka.actor.typed.Scheduler,
    ec: Executor
):

  import Monitor.*

  def start(): Unit =

    val version  = System.getProperty("java.version")
    val memory   = Runtime.getRuntime.maxMemory() / 1024 / 1024
    val native   = config.getBoolean("netty.native")
    val useKamon = config.getString("kamon.influxdb.hostname").nonEmpty

    logger.info(s"lila-ws 3.0 netty native=$native kamon=$useKamon")
    logger.info(s"Java version: $version, memory: ${memory}MB")

    if (useKamon) kamon.Kamon.init()

    scheduler.scheduleWithFixedDelay(5.seconds, 1949.millis) { () => periodicMetrics() }

    scheduler.scheduleWithFixedDelay(1 minute, 1 minute) { () => jvmThreads() }

  private def periodicMetrics() =
    val members = LilaWsServer.connections.get
    val rounds  = services.roundCrowd.size
    services.lobby.pong.update(members, rounds)
    connection.current update members.toDouble
    historyRoomSize.update(History.room.size().toDouble)
    historyRoundSize.update(History.round.size().toDouble)
    crowdRoomSize.update(services.roomCrowd.size.toDouble)
    crowdRoundSize.update(rounds.toDouble)
    usersSize.update(services.users.size.toDouble)
    watchSize.update(Fens.size.toDouble)
    busSize.update(Bus.size.toDouble)
    busAllSize.update(Bus.sizeOf(_.all).toDouble)

  private def jvmThreads() =
    val perState = Kamon.gauge("jvm.threads.group")
    val total    = Kamon.gauge("jvm.threads.group.total")
    for
      group <- ornicar.scalalib.Jvm.threadGroups()
      _ = total.withTags(TagSet.from(Map("name" -> group.name))).update(group.total)
      (state, count) <- group.states
    yield perState.withTags(TagSet.from(Map("name" -> group.name, "state" -> state.toString))).update(count)

object Monitor:

  private val logger = Logger(getClass)

  object connection:
    val current = Kamon.gauge("connection.current").withoutTags()
    def open(endpoint: String) =
      Kamon.counter("connection.open").withTag("endpoint", endpoint).increment()

  def clientOutWrongHole  = Kamon.counter("client.out.wrongHole").withoutTags()
  def clientOutUnexpected = Kamon.counter("client.out.unexpected").withoutTags()
  def clientOutUnhandled(name: String) =
    Kamon
      .counter("client.out.unhandled")
      .withTag("name", name)
      .increment()

  val historyRoomSize  = Kamon.gauge("history.room.size").withoutTags()
  val historyRoundSize = Kamon.gauge("history.round.size").withoutTags()

  val crowdRoomSize  = Kamon.gauge("crowd.room.size").withoutTags()
  val crowdRoundSize = Kamon.gauge("crowd.round.size").withoutTags()
  val usersSize      = Kamon.gauge("users.size").withoutTags()
  val watchSize      = Kamon.gauge("watch.size").withoutTags()

  val palantirChannels = Kamon.gauge("palantir.channels.size").withoutTags()

  val busSize    = Kamon.gauge("bus.size").withoutTags()
  val busAllSize = Kamon.gauge("bus.all.size").withoutTags()

  val chessMoveTime = Kamon.timer("chess.analysis.move.time").withoutTags()
  val chessDestTime = Kamon.timer("chess.analysis.dest.time").withoutTags()

  def websocketError(name: String) =
    Kamon.counter("websocket.error").withTag("name", name).increment()

  def rateLimit(name: String) =
    Kamon
      .counter("ratelimit")
      .withTag("name", name)
      .increment()

  object redis:
    val publishTime      = Kamon.timer("redis.publish.time").withoutTags()
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

  object ping:

    private def apply(chan: String) = Kamon.timer("ping").withTag("chan", chan)

    def record(chan: String, at: UptimeMillis): Int =
      val millis = at.toNow
      apply(chan).record(millis, TimeUnit.MILLISECONDS)
      millis.toInt

  object lag:

    private val frameLagHistogram = Kamon.timer("round.lag.frame").withoutTags()

    def roundFrameLag(millis: Int) =
      if (millis > 1 && millis < 99999) frameLagHistogram.record(millis.toLong, TimeUnit.MILLISECONDS)

  def time[A](metric: Monitor.type => kamon.metric.Timer)(f: => A): A =
    val timer = metric(Monitor).start()
    val res   = f
    timer.stop()
    res

  object evalCache:
    private val r = Kamon.counter("evalCache.request")
    def request(ply: Int, isHit: Boolean) =
      r.withTags(TagSet.from(Map("ply" -> (if (ply < 15) ply.toString else "15+"), "hit" -> isHit)))
    object upgrade:
      val count     = Kamon.counter("evalCache.upgrade.count").withoutTags()
      val members   = Kamon.gauge("evalCache.upgrade.members").withoutTags()
      val evals     = Kamon.gauge("evalCache.upgrade.evals").withoutTags()
      val expirable = Kamon.gauge("evalCache.upgrade.expirable").withoutTags()
