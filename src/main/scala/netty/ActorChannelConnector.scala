package lila.ws
package netty

import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.handler.codec.http.websocketx.*
import io.netty.util.concurrent.{ Future as NettyFuture, GenericFutureListener }
import org.apache.pekko.actor.typed.ActorRef

import lila.ws.Controller.Endpoint
import lila.ws.netty.ProtocolHandler.key

final private class ActorChannelConnector(clients: ActorRef[Clients.Control], loop: WorkerLoop)(using
    Executor
):

  def apply(endpoint: Endpoint, channel: Channel): Unit =
    val clientPromise = Promise[Client]()
    channel.attr(key.client).set(clientPromise.future)
    val channelEmit: ClientEmit =
      emitToChannel(channel, withFlush = endpoint.name == "round/play")
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

  private def emitToChannel(channel: Channel, withFlush: Boolean): ClientEmit =
    msg =>
      msg.match
        case ipc.ClientIn.Disconnect(reason)    => emitDisconnect(channel, reason)
        case ipc.ClientIn.RoundPingFrameNoFlush => emitPingFrame(channel)
        case in =>
          if withFlush then channel.writeAndFlush(TextWebSocketFrame(in.write))
          else loop.writeShaped(channel, TextWebSocketFrame(in.write))
