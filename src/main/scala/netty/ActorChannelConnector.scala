package lila.ws
package netty

import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.handler.codec.http.websocketx.*
import io.netty.util.concurrent.{ Future as NettyFuture, GenericFutureListener }
import org.apache.pekko.actor.typed.ActorRef

import lila.ws.Controller.Endpoint
import lila.ws.netty.ProtocolHandler.key
import lila.ws.util.RunPeriodically

final private class ActorChannelConnector(
    clients: ActorRef[Clients.Control],
    runPeriodically: RunPeriodically
)(using Executor):

  def apply(endpoint: Endpoint, channel: Channel, alwaysFlush: Boolean): Unit =
    val clientPromise = Promise[Client]()
    channel.attr(key.client).set(clientPromise.future)
    val channelEmit: ClientEmit =
      if alwaysFlush then emitToChannel(channel)
      else emitToChannelWithPeriodicFlush(channel)
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

  private inline def emitDisconnect(inline channel: Channel, inline reason: String): Unit =
    channel
      .writeAndFlush(CloseWebSocketFrame(WebSocketCloseStatus(4010, reason)))
      .addListener(ChannelFutureListener.CLOSE)

  private inline def emitPingFrame(inline channel: Channel): Unit =
    channel.write { PingWebSocketFrame(Unpooled.copyLong(System.currentTimeMillis())) }

  private def emitToChannel(channel: Channel): ClientEmit =
    case ipc.ClientIn.Disconnect(reason)    => emitDisconnect(channel, reason)
    case ipc.ClientIn.RoundPingFrameNoFlush => emitPingFrame(channel)
    case in                                 => channel.writeAndFlush(TextWebSocketFrame(in.write))

  private def emitToChannelWithPeriodicFlush(channel: Channel): ClientEmit =
    val periodicFlush = runPeriodically(5, 3.seconds)(() => channel.flush())
    msg =>
      msg.match
        case ipc.ClientIn.Disconnect(reason)    => emitDisconnect(channel, reason)
        case ipc.ClientIn.RoundPingFrameNoFlush => emitPingFrame(channel)
        case in =>
          channel.write(TextWebSocketFrame(in.write))
          periodicFlush.increment()
