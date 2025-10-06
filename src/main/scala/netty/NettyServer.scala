package lila.ws
package netty

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.epoll.{ EpollEventLoopGroup, EpollServerSocketChannel }
import io.netty.channel.kqueue.{ KQueueEventLoopGroup, KQueueServerSocketChannel }
import io.netty.channel.{ Channel, ChannelInitializer }
import io.netty.handler.codec.http.*

final class NettyServer(
    clients: ClientSystem,
    router: Router,
    config: Config,
    settings: util.SettingStore
)(using Executor, Scheduler):
  private val logger = Logger(getClass)
  private val threads = config.getInt("netty.threads")
  private val (parent, workers, channelClass) =
    if System.getProperty("os.name").toLowerCase.startsWith("mac") then
      (new KQueueEventLoopGroup(1), new KQueueEventLoopGroup(threads), classOf[KQueueServerSocketChannel])
    else (new EpollEventLoopGroup(1), new EpollEventLoopGroup(threads), classOf[EpollServerSocketChannel])
  private val connector = ActorChannelConnector(clients, config, settings, workers)

  def start(): Unit =

    logger.info("Start")
    val port = config.getInt("http.port")
    val useNginxForwardedIp = config.getBoolean("http.use-nginx-forwarded-ip")
    try
      val boot = new ServerBootstrap
      boot
        .group(parent, workers)
        .channel(channelClass)
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

      val server = boot.bind(port).sync().channel()

      logger.info(s"Listening to $port")

      server.closeFuture().sync()

      logger.info(s"Closed $port")
    finally
      parent.shutdownGracefully()
      workers.shutdownGracefully()
