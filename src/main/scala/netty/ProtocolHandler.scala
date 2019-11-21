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
import java.net.InetSocketAddress
import scala.concurrent.{ Promise, ExecutionContext }

private final class ProtocolHandler(
    clients: ActorRef[Clients.Control],
    router: Router,
    address: InetSocketAddress
)(implicit ec: ExecutionContext) extends WebSocketServerProtocolHandler(
  "/", // path
  null, // subprotocols (?)
  true, // allowExtensions (?)
  2048, // max frame size
  false, // allowMaskMismatch (?)
  true, // checkStartsWith
  true // dropPongFrames
) {

  import ProtocolHandler._

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: java.lang.Object): Unit = {
    ctx fireUserEventTriggered evt
    val promise = Promise[Client]
    ctx.channel.attr(Clients.attrKey).set(promise.future)
    evt match {
      case hs: WebSocketServerProtocolHandler.HandshakeComplete =>
        // Monitor.count.handshake.inc
        router(
          hs.requestUri,
          hs.requestHeaders,
          ctx.channel,
          IpAddress(address.getAddress.getHostAddress)
        ) foreach {
            case Left(status) =>
              sendSimpleErrorResponse(ctx, status)
              promise failure new Exception("Router refused the connection")
            case Right(actor) => connectActorToChannel(actor, ctx.channel, promise)
          }
      case _ =>
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

  private def connectActorToChannel(actor: ClientBehavior, channel: Channel, promise: Promise[Client]): Unit = {
    clients ! Clients.Start(actor, channel.id.asShortText, promise)
    channel.closeFuture.addListener(new GenericFutureListener[Future[Void]] {
      def operationComplete(f: Future[Void]): Unit =
        Option(channel.attr(Clients.attrKey).get) match {
          case Some(client) => client foreach { c =>
            clients ! Clients.Stop(c)
          }
          case None => logger.warn(s"No client actor to stop!")
        }
    })
  }
}

private object ProtocolHandler {

  private val logger = Logger(getClass)
}
