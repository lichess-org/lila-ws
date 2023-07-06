package lila.ws
package netty

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.{ Channel, ChannelInitializer }
import io.netty.channel.epoll.{ EpollEventLoopGroup, EpollServerSocketChannel }
import io.netty.channel.kqueue.{ KQueueEventLoopGroup, KQueueServerSocketChannel }
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*

final class NettyServer(
    clients: ClientSystem,
    router: Router,
    config: Config
)(using Executor):

  private val connector = ActorChannelConnector(router, clients)
  private val logger    = Logger(getClass)

  def start(): Unit =

    logger.info("Start")

    val port          = config.getInt("http.port")
    val workerThreads = config.getInt("netty.threads")

    val (bossGroup, workerGroup, channelClz) =
      if !config.getBoolean("netty.native") then
        (
          NioEventLoopGroup(1),
          NioEventLoopGroup(workerThreads),
          classOf[NioServerSocketChannel]
        )
      else if System.getProperty("os.name").toLowerCase.startsWith("mac") then
        (
          KQueueEventLoopGroup(1),
          KQueueEventLoopGroup(workerThreads),
          classOf[KQueueServerSocketChannel]
        )
      else
        (
          EpollEventLoopGroup(1),
          EpollEventLoopGroup(workerThreads),
          classOf[EpollServerSocketChannel]
        )

    try
      val boot = new ServerBootstrap
      boot
        .group(bossGroup, workerGroup)
        .channel(channelClz)
        .childHandler(
          new ChannelInitializer[Channel]:
            override def initChannel(ch: Channel): Unit =
              val pipeline = ch.pipeline()
              pipeline.addLast(HttpServerCodec())
              pipeline.addLast(HttpObjectAggregator(4096))
              pipeline.addLast(RequestHandler(router))
              pipeline.addLast(ProtocolHandler(connector))
              pipeline.addLast(FrameHandler(connector))
        )

      val server = boot.bind(port).sync().channel()

      logger.info(s"Listening to $port")

      server.closeFuture().sync()

      logger.info(s"Closed $port")
    finally
      bossGroup.shutdownGracefully()
      workerGroup.shutdownGracefully()
