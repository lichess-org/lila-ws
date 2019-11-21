package lila.ws

import akka.actor.typed.{ ActorSystem, Scheduler }
import com.google.inject.{ AbstractModule, Guice, Provides }
import com.typesafe.config.{ Config, ConfigFactory }
import javax.inject._
import scala.concurrent.ExecutionContext

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
    monitor: Monitor
) {

  def start: Unit = {
    monitor.start
    lila registerHandlers lilaHandler.handlers
    nettyServer.start
  }
}
