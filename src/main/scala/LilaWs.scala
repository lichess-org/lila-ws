package lila.ws

import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, Scheduler }
import com.google.inject.name.Names
import com.google.inject.{ AbstractModule, Guice, Provides }
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.scalalogging.Logger
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.epoll.{ EpollChannelOption, EpollEventLoopGroup, EpollServerSocketChannel }
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.SelfSignedCertificate
import javax.inject._
import scala.concurrent.ExecutionContext

final class Module extends AbstractModule {

  @Provides
  def config: Config = ConfigFactory.load

  @Provides
  def clientSystem: ClientSystem = ActorSystem(Clients.start, "clients")

  @Provides
  def scheduler: Scheduler = clientSystem.scheduler

  @Provides
  def executionContext: ExecutionContext = clientSystem.executionContext
}

object LilaWs extends App {

  private val injector = Guice.createInjector(new Module)
  private val server = injector.getInstance(classOf[LilaWsServer])

  server.run
}

@Singleton
class LilaWsServer @Inject() (
    router: Router,
    config: Config,
    lila: Lila,
    lilaHandler: LilaHandler,
    monitor: Monitor,
    system: ClientSystem
) {

  private val logger = Logger(getClass)

  def run: Unit = {

    monitor.start
    lila registerHandlers lilaHandler.handlers

    val PORT = config.getInt("http.port")

    val ssl = if (config.getBoolean("http.ssl")) Some {
      val ssc = new SelfSignedCertificate
      SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build()
    }
    else None

    val bossGroup = new EpollEventLoopGroup
    val workerGroup = new EpollEventLoopGroup
    try {
      val b = new ServerBootstrap
      b.group(bossGroup, workerGroup)
        .channel(classOf[EpollServerSocketChannel])
        .childHandler(new netty.ServerInit(system, ssl, router))

      val ch = b.bind(PORT).sync().channel()

      logger.info(s"Listening to $PORT")

      ch.closeFuture().sync()

      logger.info(s"Closed $PORT")
    }
    finally {
      bossGroup.shutdownGracefully()
      workerGroup.shutdownGracefully()
    }
  }
}
