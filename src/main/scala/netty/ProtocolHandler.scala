package lila.ws
package netty

import io.netty.channel.*
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.websocketx.*
import io.netty.handler.codec.TooLongFrameException
import io.netty.util.AttributeKey
import java.io.IOException
import akka.actor.typed.ActorRef
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.util.concurrent.{ Future as NettyFuture, GenericFutureListener }
import scala.concurrent.{ ExecutionContext, Future, Promise }
import io.netty.buffer.Unpooled

final private class ProtocolHandler(
    clients: ActorRef[Clients.Control]
)(using ec: ExecutionContext)
    extends WebSocketServerProtocolHandler(
      "",    // path
      null,  // subprotocols (?)
      false, // allowExtensions (?)
      8192,  // max frame size - /inbox allows sending 8000 chars
      false, // allowMaskMismatch (?)
      true,  // checkStartsWith
      false  // dropPongFrames
    ):

  import ProtocolHandler.*
  import Controller.Endpoint

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: java.lang.Object): Unit =
    evt match
      case hs: WebSocketServerProtocolHandler.HandshakeComplete =>
        // Monitor.count.handshake.inc
        connectActorToChannel(ctx.channel.attr(key.endpoint).get, ctx.channel)
      case _ =>
    super.userEventTriggered(ctx, evt)

  private def connectActorToChannel(
      endpoint: Endpoint,
      channel: Channel
  ): Unit =
    val promise = Promise[Client]()
    channel.attr(key.client).set(promise.future)
    channel.attr(key.limit).set(endpoint.rateLimit)
    clients ! Clients.Control.Start(endpoint.behavior(emitToChannel(channel)), promise)
    channel.closeFuture.addListener(new GenericFutureListener[NettyFuture[Void]] {
      def operationComplete(f: NettyFuture[Void]): Unit =
        Option(channel.attr(key.client).get) match {
          case Some(client) =>
            client foreach { c => clients ! Clients.Control.Stop(c) }
          case None => Monitor.websocketError("clientActorMissing")
        }
    })

  private def emitToChannel(channel: Channel): ClientEmit =
    in => {
      if (in == ipc.ClientIn.Disconnect) terminateConnection(channel)
      else if (in == ipc.ClientIn.RoundPingFrameNoFlush)
        channel.write {
          new PingWebSocketFrame(Unpooled copyLong System.currentTimeMillis())
        }
      else
        channel.writeAndFlush {
          new TextWebSocketFrame(in.write)
        }
    }

  // nicely terminate an ongoing connection
  private def terminateConnection(channel: Channel): ChannelFuture =
    channel.writeAndFlush(new CloseWebSocketFrame).addListener(ChannelFutureListener.CLOSE)

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    cause match
      // IO exceptions happen all the time, it usually just means that the client has closed the connection before fully
      // sending/receiving the response.
      case _: IOException =>
        Monitor.websocketError("io")
        ctx.close()
      // Includes normal GET requests without WebSocket upgrade
      case _: WebSocketHandshakeException =>
        val response = new DefaultFullHttpResponse(
          HttpVersion.HTTP_1_1,
          HttpResponseStatus.BAD_REQUEST,
          ctx.alloc.buffer(0)
        )
        val f = ctx.writeAndFlush(response)
        f.addListener(ChannelFutureListener.CLOSE)
      case e: CorruptedWebSocketFrameException
          if Option(e.getMessage).exists(_ startsWith "Max frame length") =>
        Monitor.websocketError("frameLength")
        ctx.close()
      case _: CorruptedWebSocketFrameException =>
        Monitor.websocketError("corrupted")
        ctx.close()
      case _: TooLongFrameException =>
        Monitor.websocketError("uriTooLong")
        ctx.close()
      case e: IllegalArgumentException
          if Option(e.getMessage).exists(_ contains "Header value contains a prohibited character") =>
        Monitor.websocketError("headerIllegalChar")
        ctx.close()
      case _ =>
        Monitor.websocketError("other")
        super.exceptionCaught(ctx, cause)

private object ProtocolHandler:

  object key:
    val endpoint = AttributeKey.valueOf[Controller.Endpoint]("endpoint")
    val client   = AttributeKey.valueOf[Future[Client]]("client")
    val limit    = AttributeKey.valueOf[RateLimit]("limit")
