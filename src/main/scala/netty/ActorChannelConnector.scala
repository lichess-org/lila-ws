package lila.ws
package netty

import lila.ws.Controller.Endpoint
import org.apache.pekko.actor.typed.ActorRef
import io.netty.channel.*
import ProtocolHandler.key
import io.netty.util.concurrent.{ Future as NettyFuture, GenericFutureListener }
import ipc.ClientIn
import io.netty.handler.codec.http.websocketx.*
import io.netty.buffer.Unpooled
import lila.ws.util.RequestUri

final private class ActorChannelConnector(router: Router, clients: ActorRef[Clients.Control])(using Executor):

  def apply(endpoint: Endpoint, channel: Channel): Unit =
    val clientPromise = Promise[Client]()
    channel.attr(key.client).set(clientPromise.future)
    clients ! Clients.Control.Start(endpoint.behavior(emitToChannel(channel)), clientPromise)
    channel.closeFuture.addListener:
      new GenericFutureListener[NettyFuture[Void]]:
        def operationComplete(f: NettyFuture[Void]): Unit =
          channel.attr(key.client).get foreach { client =>
            clients ! Clients.Control.Stop(client)
          }

  private def emitToChannel(channel: Channel): ClientEmit =
    case ipc.ClientIn.Disconnect =>
      channel.writeAndFlush(CloseWebSocketFrame()).addListener(ChannelFutureListener.CLOSE)
    case ipc.ClientIn.RoundPingFrameNoFlush =>
      channel.write { PingWebSocketFrame(Unpooled copyLong System.currentTimeMillis()) }
    case in =>
      channel.writeAndFlush(TextWebSocketFrame(in.write))
