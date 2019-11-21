package lila.ws
package netty

import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.{ HttpObjectAggregator, HttpServerCodec }
import io.netty.handler.codec.TooLongFrameException
import java.io.IOException
// import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import com.typesafe.scalalogging.Logger
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.ssl.SslContext
import io.netty.util.concurrent.{ Future, GenericFutureListener }

class ServerInit(
    clients: ClientSystem,
    sslCtx: Option[SslContext],
    router: Router
) extends ChannelInitializer[SocketChannel] {

  implicit def ec = clients.executionContext

  private val logger = Logger(getClass)

  override def initChannel(ch: SocketChannel): Unit = {
    val pipeline = ch.pipeline()
    sslCtx foreach { ssl =>
      pipeline.addLast(ssl.newHandler(ch.alloc()))
    }
    pipeline.addLast(new HttpServerCodec)
    pipeline.addLast(new HttpObjectAggregator(4096)) // 8192?
    // pipeline.addLast(new WebSocketServerCompressionHandler())
    pipeline.addLast(new WebSocketServerProtocolHandler(
      "/", // path
      null, // subprotocols (?)
      true, // allowExtensions (?)
      2048, // max frame size
      false, // allowMaskMismatch (?)
      true, // checkStartsWith
      true // dropPongFrames
    ) {
      override def userEventTriggered(ctx: ChannelHandlerContext, evt: java.lang.Object): Unit = {
        ctx fireUserEventTriggered evt
        evt match {
          case hs: WebSocketServerProtocolHandler.HandshakeComplete =>
            println("handshake")
            router(hs.requestUri, hs.requestHeaders, ctx.channel) foreach {
              case Left(status) => sendSimpleErrorResponse(ctx, status)
              case Right(actor) => connectActorToChannel(actor, ctx.channel)
            }
          case evt => println(evt)
        }
      }
      override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
        cause match {
          // IO exceptions happen all the time, it usually just means that the client has closed the connection before fully
          // sending/receiving the response.
          case e: IOException =>
            logger.trace("Benign IO exception caught in Netty", e)
            ctx.channel().close()
          case e: TooLongFrameException =>
            logger.warn("Handling TooLongFrameException", e)
            sendSimpleErrorResponse(ctx, HttpResponseStatus.REQUEST_URI_TOO_LONG)
          case e: IllegalArgumentException if Option(e.getMessage).exists(_.contains("Header value contains a prohibited character")) =>
            sendSimpleErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST)
          case e =>
            logger.error("Exception in Netty", e)
            super.exceptionCaught(ctx, cause)
        }
      }
      private def sendSimpleErrorResponse(ctx: ChannelHandlerContext, status: HttpResponseStatus): ChannelFuture = {
        val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status)
        response.headers.set(HttpHeaderNames.CONNECTION, "close")
        response.headers.set(HttpHeaderNames.CONTENT_LENGTH, "0")
        val f = ctx.channel.write(response)
        f.addListener(ChannelFutureListener.CLOSE)
        f
      }
    })
    pipeline.addLast(new FrameHandler(clients))
  }

  private def connectActorToChannel(actor: ClientBehavior, channel: Channel): Unit = {
    clients ! Clients.Start(actor, channel)
    channel.closeFuture.addListener(new GenericFutureListener[Future[Void]] {
      def operationComplete(f: Future[Void]): Unit =
        Option(channel.attr(Clients.attrKey).get) match {
          case Some(client) => clients ! Clients.Stop(client)
          case None => println(s"No client actor to stop!")
        }
    })
  }
}
