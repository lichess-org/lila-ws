package lila.ws
package netty

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.{ Channel, ChannelInitializer }
import io.netty.channel.epoll.{ EpollEventLoopGroup, EpollServerSocketChannel }
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http._
import scala.concurrent.ExecutionContext

final class NettyServer(
    clients: ClientSystem,
    router: Router,
    config: Config
)(implicit ec: ExecutionContext) {

  private val logger = Logger(getClass)

  def start(): Unit = {

    logger.info("Start")

    val port     = config.getInt("http.port")
    val useEpoll = config.getBoolean("netty.useEpoll")

    val bossGroup =
      if (useEpoll) new EpollEventLoopGroup(1)
      else new NioEventLoopGroup(1)
    val workerGroup =
      if (useEpoll) new EpollEventLoopGroup
      else new NioEventLoopGroup

    val channelClz =
      if (useEpoll) classOf[EpollServerSocketChannel]
      else classOf[NioServerSocketChannel]

    try {
      val boot = new ServerBootstrap
      boot
        .group(bossGroup, workerGroup)
        .channel(channelClz)
        .childHandler(new ChannelInitializer[Channel] {
          override def initChannel(ch: Channel): Unit = {
            val pipeline = ch.pipeline()
            pipeline.addLast(new HttpServerCodec)
            pipeline.addLast(new HttpObjectAggregator(4096))
            pipeline.addLast(new ProtocolHandler(clients, router))
            pipeline.addLast(new FrameHandler)
          }
        })

      val server = boot.bind(port).sync().channel()

      logger.info(s"Listening to $port")

      server.closeFuture().sync()

      logger.info(s"Closed $port")
    } finally {
      bossGroup.shutdownGracefully()
      workerGroup.shutdownGracefully()
    }

  }
}
