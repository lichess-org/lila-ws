package lila.ws

import akka.actor.typed.{ ActorSystem, Scheduler }
import com.softwaremill.macwire._
import com.typesafe.config.{ Config, ConfigFactory }
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import util.Util.nowSeconds

object Boot extends App {

  lazy val config: Config                         = ConfigFactory.load
  lazy val clientSystem: ClientSystem             = ActorSystem(Clients.behavior, "clients")
  implicit def scheduler: Scheduler               = clientSystem.scheduler
  implicit def executionContext: ExecutionContext = clientSystem.executionContext

  lazy val mongo         = wire[Mongo]
  lazy val groupedWithin = wire[util.GroupedWithin]
  lazy val lightUserApi  = wire[LightUserApi]
  lazy val lilaRedis     = wire[Lila]
  lazy val lilaHandlers  = wire[LilaHandler]
  lazy val roundCrowd    = wire[RoundCrowd]
  lazy val roomCrowd     = wire[RoomCrowd]
  lazy val crowdJson     = wire[ipc.CrowdJson]
  lazy val users         = wire[Users]
  lazy val keepAlive     = wire[KeepAlive]
  lazy val lobby         = wire[Lobby]
  lazy val socialGraph   = wire[SocialGraph]
  lazy val friendList    = wire[FriendList]
  lazy val services      = wire[Services]
  lazy val controller    = wire[Controller]
  lazy val router        = wire[Router]
  lazy val seenAt        = wire[SeenAtUpdate]
  lazy val auth          = wire[Auth]
  lazy val nettyServer   = wire[netty.NettyServer]
  lazy val monitor       = wire[Monitor]

  wire[LilaWsServer].start
}

final class LilaWsServer(
    nettyServer: netty.NettyServer,
    handlers: LilaHandler, // must eagerly instanciate!
    lila: Lila,
    monitor: Monitor,
    scheduler: Scheduler
)(implicit ec: ExecutionContext) {

  def start(): Unit = {

    monitor.start()

    Bus.internal.subscribe(
      "users",
      {
        case ipc.LilaIn.ConnectUser(_, true) => // don't send to lila
        case msg: ipc.LilaIn.Site            => lila.emit.site(msg)
      }
    )

    scheduler.scheduleWithFixedDelay(30.seconds, 7211.millis) { () =>
      Bus.publish(_.all, ipc.ClientCtrl.Broom(nowSeconds - 30))
    }

    nettyServer.start() // blocks
  }
}

object LilaWsServer {

  val connections = new java.util.concurrent.atomic.AtomicInteger
}
