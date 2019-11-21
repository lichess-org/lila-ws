package lila.ws
package netty

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.epoll.{ EpollEventLoopGroup, EpollServerSocketChannel }
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http._
import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.handler.ssl.{ SslContext, SslContextBuilder }
import javax.inject._
import scala.concurrent.ExecutionContext

@Singleton
final class NettyServer @Inject() (
    clients: ClientSystem,
    router: Router,
    config: Config
)(implicit ec: ExecutionContext) {

  val logger = Logger(getClass)

  def start: Unit = {

    logger.info("Start")

    val port = config.getInt("http.port")

    val sslCtx = if (config.getBoolean("http.ssl")) Some {
      val ssc = new SelfSignedCertificate
      SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build()
    }
    else None

    val bossGroup = new EpollEventLoopGroup(1) // 1 like in the netty examples (?)
    val workerGroup = new EpollEventLoopGroup

    try {
      val boot = new ServerBootstrap
      boot.group(bossGroup, workerGroup)
        .channel(classOf[EpollServerSocketChannel])
        .childHandler(new ChannelInitializer[SocketChannel] {
          override def initChannel(ch: SocketChannel): Unit = {
            val pipeline = ch.pipeline()
            sslCtx foreach { ssl =>
              pipeline.addLast(ssl.newHandler(ch.alloc()))
            }
            pipeline.addLast(new HttpServerCodec)
            pipeline.addLast(new HttpObjectAggregator(4096)) // 8192?
            // pipeline.addLast(new WebSocketServerCompressionHandler())
            pipeline.addLast(new ProtocolHandler(clients, router, ch.localAddress))
            pipeline.addLast(new FrameHandler(clients))
          }
        })

      val server = boot.bind(port).sync().channel()

      logger.info(s"Listening to $port")

      server.closeFuture().sync()

      logger.info(s"Closed $port")
    }
    finally {
      bossGroup.shutdownGracefully()
      workerGroup.shutdownGracefully()
    }

  }
}
