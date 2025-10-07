package lila.ws

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import kamon.Kamon
import kamon.metric.Counter
import kamon.tag.TagSet

import java.util.concurrent.TimeUnit
import scala.language.implicitConversions

import lila.ws.util.Domain

final class Monitor(
    config: Config,
    services: Services
)(using scheduler: Scheduler, ec: Executor):

  import Monitor.*

  def start(): Unit =

    val version = System.getProperty("java.version")
    val memory = Runtime.getRuntime.maxMemory() / 1024 / 1024
    val useKamon = config.getString("kamon.influxdb.hostname").nonEmpty
    val gitCommit = BuildInfo.gitHeadCommit.take(7)

    logger.info(s"lila-ws 3.0 netty kamon=$useKamon")
    logger.info(s"Git commit: ${gitCommit}, java version: $version, memory: ${memory}MB")

    if useKamon then kamon.Kamon.initWithoutAttaching()

    scheduler.scheduleWithFixedDelay(5.seconds, 1949.millis) { () => periodicMetrics() }

    scheduler.scheduleWithFixedDelay(1.minute, 1.minute): () =>
      jvmThreads()
      LilaRequest.monitorInFlight()

  private def periodicMetrics() =
    val members = LilaWsServer.connections.get
    val rounds = services.roundCrowd.size()
    services.lobby.pong.update(members, rounds)
    connection.current.update(members.toDouble)
    historyRoomSize.update(History.room.size().toDouble)
    historyRoundSize.update(History.round.size().toDouble)
    crowdRoomSize.update(services.roomCrowd.size().toDouble)
    crowdRoundSize.update(rounds.toDouble)
    usersSize.update(services.users.size().toDouble)
    watchSize.update(Fens.size().toDouble)
    busSize.update(Bus.size().toDouble)
    busAllSize.update(Bus.sizeOf(_.all).toDouble)

  private def jvmThreads() =
    val perState = Kamon.gauge("jvm.threads.group")
    val total = Kamon.gauge("jvm.threads.group.total")
    for
      group <- scalalib.Jvm.threadGroups()
      _ = total.withTags(TagSet.from(Map("name" -> group.name))).update(group.total)
      (state, count) <- group.states
    yield perState.withTags(TagSet.from(Map("name" -> group.name, "state" -> state.toString))).update(count)

object Monitor:

  private val logger = Logger(getClass)

  object connection:
    val current = Kamon.gauge("connection.current").withoutTags()
    def open(endpoint: String) =
      Kamon.counter("connection.open").withTag("endpoint", endpoint).increment()

  def clientOutWrongHole = Kamon.counter("client.out.wrongHole").withoutTags()
  def clientOutUnexpected = Kamon.counter("client.out.unexpected").withoutTags()
  def clientOutUnhandled(name: String) =
    Kamon
      .counter("client.out.unhandled")
      .withTag("name", name)
      .increment()

  def clientInCounter(endpointName: String): Counter =
    Kamon.counter("client.in").withTag("name", endpointName)

  val historyRoomSize = Kamon.gauge("history.room.size").withoutTags()
  val historyRoundSize = Kamon.gauge("history.round.size").withoutTags()

  val crowdRoomSize = Kamon.gauge("crowd.room.size").withoutTags()
  val crowdRoundSize = Kamon.gauge("crowd.round.size").withoutTags()
  val usersSize = Kamon.gauge("users.size").withoutTags()
  val watchSize = Kamon.gauge("watch.size").withoutTags()

  val voiceChatChannels = Kamon.gauge("voiceChat.channels.size").withoutTags()

  val busSize = Kamon.gauge("bus.size").withoutTags()
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
    val publishTime = Kamon.timer("redis.publish.time").withoutTags()
    private val countIn = Kamon.counter("redis.in")
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
    object request:
      def send() = Kamon.counter("redis.request.send").withoutTags().increment()
      def inFlight(nb: Int) = Kamon.gauge("redis.request.inFlight").withoutTags().update(nb)
      def expired(cause: String) =
        Kamon
          .counter("redis.request.expired")
          .withTag("cause", cause)
          .increment()

  object ping:

    private def apply(chan: String) = Kamon.timer("ping").withTag("chan", chan)

    def record(chan: String, at: UptimeMillis): Int =
      val millis = at.toNow
      apply(chan).record(millis, TimeUnit.MILLISECONDS)
      millis.toInt

  object lag:

    private val frameLagHistogram = Kamon.timer("round.lag.frame")

    def roundFrameLag(millis: Int, domain: Domain) =
      if millis > 1 && millis < 99999 then
        frameLagHistogram.withTag("domain", domain.value).record(millis.toLong, TimeUnit.MILLISECONDS)

  object mobile:
    private val Regex = """Lichess Mobile/(\S+)(?: \(\d*\))? as:(\S+) sri:\S+ os:(Android|iOS)/.*""".r
    private val counter = Kamon.counter("mobile.connect")
    def connect(req: util.RequestHeader) = if req.isLichessMobile then
      req.userAgent match
        case Regex(version, user, osName) =>
          counter
            .withTags(
              TagSet.from(
                Map(
                  "version" -> version,
                  "os" -> osName,
                  "auth" -> (if user == "anon" then "anon" else "auth"),
                  "route" -> req.path.drop(1).takeWhile('/' !=)
                )
              )
            )
            .increment()
        case _ => ()
    object lobbySriChain:
      val loopAvoided = Kamon.counter("mobile.lobbySriChain.loopAvoided").withoutTags()
      val srisInTheChain = Kamon.histogram("mobile.lobbySriChain.srisInTheChain").withoutTags()

  def time[A](metric: Monitor.type => kamon.metric.Timer)(f: => A): A =
    val timer = metric(Monitor).start()
    val res = f
    timer.stop()
    res

  object evalCache:
    private val requests = Kamon.counter(s"evalCache.request")
    final class Style(key: String):
      def request(ply: Int, isHit: Boolean) = requests.withTags:
        TagSet.from(Map("ply" -> (if ply < 15 then ply.toString else "15+"), "hit" -> isHit, "style" -> key))
      object upgrade:
        val count = Kamon.counter("evalCache.upgrade.count").withTag("style", key)
        val members = Kamon.gauge("evalCache.upgrade.members").withTag("style", key)
        val evals = Kamon.gauge("evalCache.upgrade.evals").withTag("style", key)
        val expirable = Kamon.gauge("evalCache.upgrade.expirable").withTag("style", key)
    val single = Style("single")
    val multi = Style("multi")

  object connector:
    object flush:
      object config:
        val step = Kamon.gauge("connector.flush.config.step").withoutTags()
        val interval = Kamon.gauge("connector.flush.config.interval").withoutTags()
        val maxDelay = Kamon.gauge("connector.flush.config.maxDelay").withoutTags()
      val qSizeEstimate = Kamon.histogram("connector.flush.qSize.estimate").withoutTags()
      val channelsToFlush = Kamon.histogram("connector.flush.channelsToFlush").withoutTags()
      val loopRuntimeMicroseconds = Kamon.histogram("connector.flush.loopRuntimeMicroseconds").withoutTags()

  object handler:
    val batch = Kamon.histogram("handler.batch").withoutTags()

  def tags(elems: (String, Any)*): Map[String, Any] = Map.from(elems)
  given Conversion[Map[String, Any], TagSet] = TagSet.from
