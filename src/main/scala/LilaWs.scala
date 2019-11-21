package lila.ws

import akka.actor.typed.{ ActorSystem, Scheduler }
import com.google.inject.{ AbstractModule, Guice, Provides }
import com.typesafe.config.{ Config, ConfigFactory }
import javax.inject._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import util.Util.nowSeconds

object Boot extends App {

  private val injector = Guice.createInjector(new AbstractModule {

    @Provides def config: Config = ConfigFactory.load

    @Provides def clientSystem: ClientSystem = ActorSystem(Clients.start, "clients")

    @Provides def scheduler: Scheduler = clientSystem.scheduler

    @Provides def executionContext: ExecutionContext = clientSystem.executionContext
  })

  private val server = injector.getInstance(classOf[LilaWsServer])

  server.start
}

@Singleton
final class LilaWsServer @Inject() (
    nettyServer: netty.NettyServer,
    lila: Lila,
    lilaHandler: LilaHandler,
    monitor: Monitor,
    scheduler: Scheduler
)(implicit ec: ExecutionContext) {

  def start: Unit = {
    monitor.start
    lila registerHandlers lilaHandler.handlers

    scheduler.scheduleWithFixedDelay(30.seconds, 7211.millis) { () =>
      Bus.publish(_.all, ipc.ClientCtrl.Broom(nowSeconds - 30))
    }
    scheduler.scheduleWithFixedDelay(5.seconds, 1811.millis) { () =>
      val connections = Connections.get
      lila.emit.site(ipc.LilaIn.Connections(connections))
      Monitor.connection.current update connections
    }

    nettyServer.start // blocks
  }
}
