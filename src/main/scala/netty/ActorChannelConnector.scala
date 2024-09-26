package lila.ws
package netty

import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.handler.codec.http.websocketx.*
import io.netty.util.concurrent.{ Future as NettyFuture, GenericFutureListener }
import org.apache.pekko.actor.typed.{ ActorRef, Scheduler }

import lila.ws.Controller.Endpoint
import lila.ws.netty.ProtocolHandler.key

final private class ActorChannelConnector(
    clients: ActorRef[Clients.Control],
    staticConfig: com.typesafe.config.Config,
    settings: util.SettingStore
)(using scheduler: Scheduler, ec: Executor):

  private val flushQ  = java.util.concurrent.ConcurrentLinkedQueue[Channel]()
  private val monitor = Monitor.connector.flush

  private object config:
    private def int(key: String) = settings.makeSetting(key, staticConfig.getInt(key))
    val step                     = int("netty.flush.step")
    val interval                 = int("netty.flush.interval-millis")
    val maxDelay                 = int("netty.flush.max-delay-millis")
    inline def alwaysFlush()     = interval.get() <= 0
    scheduler.scheduleWithFixedDelay(1 minute, 1 minute): () =>
      monitor.config.step.update(step.get())
      monitor.config.interval.update(interval.get())
      monitor.config.maxDelay.update(maxDelay.get())

  scheduler.scheduleOnce(1 second, () => flush())

  def apply(endpoint: Endpoint, channel: Channel): Unit =
    val clientPromise = Promise[Client]()
    channel.attr(key.client).set(clientPromise.future)
    val channelEmit: ClientEmit =
      val emitter = emitToChannel(channel, withFlush = endpoint.alwaysFlush)
      (msg: ipc.ClientIn) =>
        endpoint.emitCounter.increment()
        emitter(msg)
    clients ! Clients.Control.Start(endpoint.behavior(channelEmit), clientPromise)
    channel.closeFuture.addListener:
      new GenericFutureListener[NettyFuture[Void]]:
        def operationComplete(f: NettyFuture[Void]): Unit =
          channel.attr(key.client).get.foreach { client =>
            clients ! Clients.Control.Stop(client)
          }

  private def emitToChannel(channel: Channel, withFlush: Boolean): ClientEmit =
    case ipc.ClientIn.Disconnect(reason) =>
      channel
        .writeAndFlush(CloseWebSocketFrame(WebSocketCloseStatus(4010, reason)))
        .addListener(ChannelFutureListener.CLOSE)
    case ipc.ClientIn.RoundPingFrameNoFlush =>
      channel.write { PingWebSocketFrame(Unpooled.copyLong(System.currentTimeMillis())) }
    case in if withFlush || config.alwaysFlush() =>
      channel.writeAndFlush(TextWebSocketFrame(in.write))
    case in =>
      channel.write(TextWebSocketFrame(in.write))
      flushQ.add(channel)

  private def flush(): Unit =
    val qSize           = flushQ.size
    val maxDelayFactor  = config.interval.get().toDouble / config.maxDelay.get().atLeast(1)
    var channelsToFlush = config.step.get().atLeast((qSize * maxDelayFactor).toInt)

    monitor.qSize.record(qSize)
    monitor.channelsToFlush.record(channelsToFlush)

    while channelsToFlush > 0 do
      Option(flushQ.poll()) match
        case Some(channel) =>
          if channel.isOpen then channel.eventLoop().execute(() => channel.flush())
          channelsToFlush -= 1
        case _ =>
          channelsToFlush = 0

    val nextInterval =
      if !config.alwaysFlush() then config.interval.get().millis
      else if flushQ.isEmpty then 1.second // hibernate
      else 1.millis                        // interval is 0 but we still need to empty the queue
    scheduler.scheduleOnce(nextInterval, () => flush())
