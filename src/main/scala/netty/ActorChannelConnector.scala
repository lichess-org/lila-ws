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
    config: com.typesafe.config.Config,
    settings: util.SettingStore
)(using
    scheduler: Scheduler,
    ec: Executor
):
  private val step = settings.makeSetting("netty.flush.step", config.getInt("netty.flush.step"))
  private val interval =
    settings.makeSetting("netty.flush.interval-millis", config.getInt("netty.flush.interval-millis"))
  private val maxDelay =
    settings.makeSetting("netty.flush.max-delay-millis", config.getInt("netty.flush.max-delay-millis"))

  private val flushQ = new java.util.concurrent.ConcurrentLinkedQueue[Channel]()

  scheduler.scheduleOnce(1 second, () => flush())

  def apply(endpoint: Endpoint, channel: Channel): Unit =
    val clientPromise = Promise[Client]()
    channel.attr(key.client).set(clientPromise.future)
    val channelEmit: ClientEmit =
      emitToChannel(channel, withFlush = endpoint.alwaysFlush)
    val monitoredEmit: ClientEmit = (msg: ipc.ClientIn) =>
      endpoint.emitCounter.increment()
      channelEmit(msg)
    clients ! Clients.Control.Start(endpoint.behavior(monitoredEmit), clientPromise)
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
    case in if withFlush || interval.get <= 0 =>
      channel.writeAndFlush(TextWebSocketFrame(in.write))
    case in =>
      channel.write(TextWebSocketFrame(in.write))
      flushQ.add(channel)

  private def maxDelayFactor: Double = interval.get.toDouble / maxDelay.get

  private def flush(): Unit =
    var channelsToFlush = step.get.atLeast((flushQ.size * maxDelayFactor).toInt)

    while channelsToFlush > 0 do
      Option(flushQ.poll()) match
        case Some(channel) =>
          if channel.isOpen then channel.eventLoop().execute(() => channel.flush())
          channelsToFlush -= 1
        case _ =>
          channelsToFlush = 0

    val nextInterval = if interval.get <= 0 then 1.second else interval.get.millis
    scheduler.scheduleOnce(nextInterval, () => flush())
