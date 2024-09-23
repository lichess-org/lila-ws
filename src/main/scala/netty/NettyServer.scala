package lila.ws
package netty

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.{ Channel, ChannelInitializer }
import io.netty.handler.codec.http.*

final class NettyServer(
    clients: ClientSystem,
    router: Router,
    config: Config,
    workerLoop: WorkerLoop
)(using Executor):

  private val logger = Logger(getClass)

  def start(): Unit =

    logger.info("Start")

    val port = config.getInt("http.port")
    try
      val boot = new ServerBootstrap
      boot
        .group(workerLoop.parentGroup, workerLoop.group)
        .channel(workerLoop.channelClass)
        .childHandler(
          new ChannelInitializer[Channel]:
            override def initChannel(ch: Channel): Unit =
              val pipeline = ch.pipeline()
              pipeline.addLast(HttpServerCodec())
              pipeline.addLast(HttpObjectAggregator(4096))
              pipeline.addLast(RequestHandler(router))
              pipeline.addLast(ProtocolHandler(ActorChannelConnector(clients, workerLoop)))
              pipeline.addLast(FrameHandler())
        )

      val server = boot.bind(port).sync().channel()

      logger.info(s"Listening to $port")

      server.closeFuture().sync()

      logger.info(s"Closed $port")
    finally workerLoop.shutdown()
