package lila.ws
package netty

import io.netty.channel.*
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.websocketx.*
import io.netty.handler.codec.TooLongFrameException
import io.netty.util.AttributeKey
import java.io.IOException
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler

final private class ProtocolHandler(connector: ActorChannelConnector)
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

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: java.lang.Object): Unit =
    evt match
      case _: WebSocketServerProtocolHandler.HandshakeComplete =>
        // Monitor.count.handshake.inc
        connector(ctx.channel.attr(key.endpoint).get, ctx.channel)
      case _ =>
    super.userEventTriggered(ctx, evt)

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    cause match
      // IO exceptions happen all the time, it usually just means that the client has closed the connection before fully
      // sending/receiving the response.
      case _: IOException =>
        Monitor.websocketError("io")
        ctx.close()
      // Includes normal GET requests without WebSocket upgrade
      case _: WebSocketHandshakeException =>
        Monitor.websocketError("handshake")
        super.exceptionCaught(ctx, cause)
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
