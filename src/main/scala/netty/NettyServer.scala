package lila.ws
package netty

import com.typesafe.config.{ Config, ConfigException }
import com.typesafe.scalalogging.Logger
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.{ Channel, ChannelInitializer }
import io.netty.handler.codec.http.*
import io.netty.util.concurrent.{ Future as NettyFuture, GenericFutureListener }
import scala.jdk.CollectionConverters.*
import io.netty.channel.ChannelFuture

final class NettyServer(
    clients: ClientSystem,
    router: Router,
    config: Config,
    settings: util.SettingStore
)(using Executor, Scheduler):
  private val logger = Logger(getClass)
  private val threads = config.getInt("netty.threads")
  private val pack = Pack.instance
  private val parent = pack.eventLoopGroupFactory(1)
  private val workers = pack.eventLoopGroupFactory(threads)
  private val connector = ActorChannelConnector(clients, config, settings, workers)

  def start(): Unit =

    logger.info("Start")

    val ports: List[Int] = try config.getIntList("http.ports").asScala.toList.map(_.toInt)
    catch case _: ConfigException => List(config.getInt("http.port"))
    logger.info(s"Ports to bind: ${ports.mkString(", ")}")

    val useNginxForwardedIp = config.getBoolean("http.use-nginx-forwarded-ip")
    try
      val boot = new ServerBootstrap
      boot
        .group(parent, workers)
        .channel(pack.channelClass)
        .childHandler(
          new ChannelInitializer[Channel]:
            override def initChannel(ch: Channel): Unit =
              val pipeline = ch.pipeline()
              pipeline.addLast(HttpServerCodec())
              pipeline.addLast(HttpObjectAggregator(4096))
              pipeline.addLast(RequestHandler(router, useNginxForwardedIp))
              pipeline.addLast(ProtocolHandler(connector))
              pipeline.addLast(FrameHandler())
        )

      val channels: List[ChannelFuture] = ports.map { p =>
        logger.info(s"Will bind port $p")
        val channel = boot.bind(p).sync().channel()
        logger.info(s"Bound port $p")
        channel
          .closeFuture()
          .addListener:
            new GenericFutureListener[NettyFuture[Void]]:
              def operationComplete(f: NettyFuture[Void]): Unit =
                logger.info(s"Unbound port $p")
      }
      channels.foreach(_.sync())
      logger.info("All ports unbound")

    finally
      parent.shutdownGracefully()
      workers.shutdownGracefully()
