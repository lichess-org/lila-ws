package lila.ws

import cats.syntax.all.*
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import io.lettuce.core.*
import io.lettuce.core.pubsub.*
import ipc.*
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Await

final class Lila(config: Config)(using Executor):

  import Lila.*

  object currentStatus:
    private var value: Status = Status.Online
    def setOffline()          = value = Status.Offline
    def setOnline() =
      value = Status.Online
      buffer.flush()
    def isOnline: Boolean = value == Status.Online

  private object buffer:
    case class Buffered(chan: String, msg: String)
    private val queue       = new ConcurrentLinkedQueue[Buffered]()
    private lazy val connIn = redis.connectPubSub

    def enqueue(chan: String, msg: String) = queue offer Buffered(chan, msg)

    @annotation.tailrec
    def flush(): Unit =
      val next = queue.poll()
      if next != null then
        connIn.async.publish(next.chan, next.msg)
        flush()

  private val logger = Logger(getClass)
  private val redis  = RedisClient create RedisURI.create(config.getString("redis.uri"))

  private val handlersPromise                  = Promise[Handlers]()
  private val futureHandlers: Future[Handlers] = handlersPromise.future
  private var handlers: Handlers               = chan => out => futureHandlers foreach { _(chan)(out) }
  def setHandlers(hs: Handlers) =
    handlers = hs
    handlersPromise success hs

  val emit: Emits = Await.result(
    util.Chronometer(connectAll).lap.map { lap =>
      logger.info(s"Redis connection took ${lap.showDuration}")
      lap.result
    },
    3.seconds
  )

  private def connectAll: Future[Emits] =
    (
      connect[LilaIn.Site](chans.site),
      connect[LilaIn.Tour](chans.tour),
      connect[LilaIn.Lobby](chans.lobby),
      connect[LilaIn.Simul](chans.simul),
      connect[LilaIn.Team](chans.team),
      connect[LilaIn.Swiss](chans.swiss),
      connect[LilaIn.Study](chans.study),
      connect[LilaIn.Round](chans.round),
      connect[LilaIn.Challenge](chans.challenge),
      connect[LilaIn.Racer](chans.racer)
    ).mapN(Emits.apply(_, _, _, _, _, _, _, _, _, _))

  private def connect[In <: LilaIn](chan: Chan): Future[Emit[In]] =

    val connIn = redis.connectPubSub

    val emit: Emit[In] = in =>
      val msg    = in.write
      val path   = msg.takeWhile(' '.!=)
      val chanIn = chan in msg
      if currentStatus.isOnline then
        connIn.async.publish(chanIn, msg)
        Monitor.redis.in(chanIn, path)
      else if in.critical then
        buffer.enqueue(chanIn, msg)
        Monitor.redis.queue(chanIn, path)
      else if in.isInstanceOf[LilaIn.RoomSetVersions]
      then connIn.async.publish(chanIn, msg)
      else Monitor.redis.drop(chanIn, path)

    chan
      .match
        case s: SingleLaneChan => connectAndSubscribe(s.out, s.out) map { _ => emit }
        case r: RoundRobinChan =>
          connectAndSubscribe(r.out, r.out) zip Future.sequence:
            (0 to r.parallelism).map: index =>
              connectAndSubscribe(s"${r.out}:$index", r.out)
      .map: _ =>
        val msg = LilaIn.WsBoot.write
        connIn.async.publish(chan in msg, msg)
        emit

  private def connectAndSubscribe(chanName: String, handlerName: String): Future[Unit] =
    val connOut = redis.connectPubSub
    connOut.addListener(
      new RedisPubSubAdapter[String, String]:
        override def message(_chan: String, msg: String): Unit =
          Monitor.redis.out(chanName, msg.takeWhile(' '.!=))
          LilaOut read msg match
            case Some(out) => handlers(handlerName)(out)
            case None      => logger.warn(s"Can't parse $msg on $chanName")
    )
    val promise = Promise[Unit]()

    connOut.async
      .subscribe(chanName)
      .thenRun: () =>
        promise success ()

    promise.future

object Lila:

  enum Status:
    case Online
    case Offline

  type Handlers = String => Emit[LilaOut]

  trait Chan:
    def in(msg: String): String
    val out: String

  sealed abstract class SingleLaneChan(name: String) extends Chan:
    def in(_msg: String) = s"$name-in"
    val out              = s"$name-out"

  sealed abstract class RoundRobinChan(name: String, val parallelism: Int) extends Chan:
    def in(msg: String) = s"$name-in:${msg.hashCode.abs % parallelism}"
    val out             = s"$name-out"

  object chans:
    object site      extends SingleLaneChan("site")
    object tour      extends SingleLaneChan("tour")
    object lobby     extends SingleLaneChan("lobby")
    object simul     extends SingleLaneChan("simul")
    object team      extends SingleLaneChan("team")
    object swiss     extends SingleLaneChan("swiss")
    object study     extends SingleLaneChan("study")
    object round     extends RoundRobinChan("r", 16)
    object challenge extends SingleLaneChan("chal")
    object racer     extends SingleLaneChan("racer")

  final class Emits(
      val site: Emit[LilaIn.Site],
      val tour: Emit[LilaIn.Tour],
      val lobby: Emit[LilaIn.Lobby],
      val simul: Emit[LilaIn.Simul],
      val team: Emit[LilaIn.Team],
      val swiss: Emit[LilaIn.Swiss],
      val study: Emit[LilaIn.Study],
      val round: Emit[LilaIn.Round],
      val challenge: Emit[LilaIn.Challenge],
      val racer: Emit[LilaIn.Racer]
  ):

    def apply[In](select: Emits => Emit[In], in: In) = select(this)(in)
