package lila.ws
package netty

import lila.ws.Controller.Endpoint
import akka.actor.typed.ActorRef
import io.netty.channel.*
import ProtocolHandler.key
import io.netty.util.concurrent.{ Future as NettyFuture, GenericFutureListener }
import ipc.ClientIn
import io.netty.handler.codec.http.websocketx.*
import io.netty.buffer.Unpooled

final private class ActorChannelConnector(router: Router, clients: ActorRef[Clients.Control])(using Executor):

  def apply(endpoint: Endpoint, channel: Channel): Unit =
    val clientPromise = Promise[Client]()
    channel.attr(key.client).set(clientPromise.future)
    clients ! Clients.Control.Start(endpoint.behavior(emitToChannel(channel)), clientPromise)
    channel.closeFuture.addListener(new GenericFutureListener[NettyFuture[Void]] {
      def operationComplete(@annotation.nowarn("msg=unused explicit parameter") f: NettyFuture[Void]): Unit =
        channel.attr(key.client).get foreach { client =>
          clients ! Clients.Control.Stop(client)
        }
    })

  def switch(client: Client, endpoint: Endpoint, channel: Channel)(uri: String) =
    val newReq = endpoint.req.switch(uri)
    if endpoint.req.uri == newReq.uri
    then client ! ClientIn.SwitchResponse(uri, 200)
    else
      router(newReq) foreach:
        case Left(status) => client ! ClientIn.SwitchResponse(uri, status.code)
        case Right(newEndpoint) =>
          val clientPromise = Promise[Client]()
          val behavior      = newEndpoint.behavior(emitToChannel(channel))
          clients ! Clients.Control.Switch(client, behavior, clientPromise)
          clientPromise.future foreach { _ ! ClientIn.SwitchResponse(uri, 200) }
          channel.attr(key.endpoint).set(newEndpoint)
          channel.attr(key.client).set(clientPromise.future)

  private def emitToChannel(channel: Channel): ClientEmit =
    case ipc.ClientIn.Disconnect =>
      channel.writeAndFlush(CloseWebSocketFrame()).addListener(ChannelFutureListener.CLOSE)
    case ipc.ClientIn.RoundPingFrameNoFlush =>
      channel.write { PingWebSocketFrame(Unpooled copyLong System.currentTimeMillis()) }
    case in =>
      channel.writeAndFlush { TextWebSocketFrame(in.write) }
