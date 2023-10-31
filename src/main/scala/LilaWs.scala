package lila.ws

import org.apache.pekko.actor.typed.{ ActorSystem, Scheduler }
import com.softwaremill.macwire.*
import com.typesafe.config.{ Config, ConfigFactory }

object LilaWs extends App:

  lazy val config: Config             = ConfigFactory.load
  lazy val clientSystem: ClientSystem = ActorSystem(Clients.behavior, "clients")
  given Scheduler                     = clientSystem.scheduler
  given Executor                      = clientSystem.executionContext

  lazy val mongo         = wire[Mongo]
  lazy val groupedWithin = wire[util.GroupedWithin]
  lazy val lightUserApi  = wire[LightUserApi]
  lazy val lilaRedis     = wire[Lila]
  lazy val inquirers     = wire[Inquirers]
  lazy val roundCrowd    = wire[RoundCrowd]
  lazy val roomCrowd     = wire[RoomCrowd]
  lazy val crowdJson     = wire[ipc.CrowdJson]
  lazy val users         = wire[Users]
  lazy val keepAlive     = wire[KeepAlive]
  lazy val lobby         = wire[Lobby]
  lazy val socialGraph   = wire[SocialGraph]
  lazy val friendList    = wire[FriendList]
  lazy val stormSign     = wire[StormSign]
  lazy val lag           = wire[Lag]
  lazy val evalCache     = wire[lila.ws.evalCache.EvalCacheApi]
  lazy val services      = wire[Services]
  lazy val controller    = wire[Controller]
  lazy val router        = wire[Router]
  lazy val seenAt        = wire[SeenAtUpdate]
  lazy val auth          = wire[Auth]
  lazy val nettyServer   = wire[netty.NettyServer]
  lazy val monitor       = wire[Monitor]

  wire[LilaHandler] // must eagerly instanciate!
  wire[LilaWsServer].start()

final class LilaWsServer(
    nettyServer: netty.NettyServer,
    lila: Lila,
    lobby: Lobby,
    monitor: Monitor,
    scheduler: Scheduler
)(using Executor):

  def start(): Unit =

    monitor.start()

    Bus.internal.subscribe(
      "users",
      {
        case ipc.LilaIn.ConnectUser(_, true) => // don't send to lila
        case msg: ipc.LilaIn.Site            => lila.emit.site(msg)
      }
    )

    scheduler.scheduleWithFixedDelay(30.seconds, 7211.millis): () =>
      Bus.publish(_.all, ipc.ClientCtrl.Broom(nowSeconds - 30))

    scheduler.scheduleWithFixedDelay(4.seconds, 1201.millis): () =>
      val counters = lobby.pong.get
      lila.emit.lobby(ipc.LilaIn.Counters(counters.members, counters.rounds))

    scheduler.scheduleWithFixedDelay(1.seconds, 1879.millis): () =>
      lila.emit.site(ipc.LilaIn.Ping(UptimeMillis.make))
      lila.emit.round(ipc.LilaIn.Ping(UptimeMillis.make))

    nettyServer.start() // blocks

object LilaWsServer:

  val connections = new java.util.concurrent.atomic.AtomicInteger
