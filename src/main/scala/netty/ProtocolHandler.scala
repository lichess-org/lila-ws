package lila.ws
package netty

import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx._
import io.netty.handler.codec.TooLongFrameException
import io.netty.util.AttributeKey
import java.io.IOException
// import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import com.typesafe.scalalogging.Logger
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.util.concurrent.{ Future => NettyFuture, GenericFutureListener }
import java.net.InetSocketAddress
import scala.concurrent.{ Future, Promise, ExecutionContext }

private final class ProtocolHandler(
    clients: ActorRef[Clients.Control],
    router: Router,
    ip: IpAddress
)(implicit ec: ExecutionContext) extends WebSocketServerProtocolHandler(
  "/", // path
  null, // subprotocols (?)
  false, // allowExtensions (?)
  2048, // max frame size
  false, // allowMaskMismatch (?)
  true, // checkStartsWith
  true // dropPongFrames
) {

  import ProtocolHandler._
  import Controller.Endpoint

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: java.lang.Object): Unit = {
    evt match {
      case hs: WebSocketServerProtocolHandler.HandshakeComplete =>
        // Monitor.count.handshake.inc
        val promise = Promise[Client]
        ctx.channel.attr(key.client).set(promise.future)
        router(
          new util.RequestHeader(hs.requestUri, hs.requestHeaders, ip),
          emitToChannel(ctx.channel)
        ) foreach {
            case Left(status) =>
              terminateConnection(ctx.channel)
              promise failure new Exception("Router refused the connection")
            case Right(client) => connectActorToChannel(client, ctx.channel, promise)
          }
      case _ =>
    }
    super.userEventTriggered(ctx, evt)
  }

  private def connectActorToChannel(endpoint: Endpoint, channel: Channel, promise: Promise[Client]): Unit = {
    channel.attr(key.limit).set(endpoint.rateLimit)
    clients ! Clients.Start(endpoint.behavior, promise)
    channel.closeFuture.addListener(new GenericFutureListener[NettyFuture[Void]] {
      def operationComplete(f: NettyFuture[Void]): Unit =
        Option(channel.attr(key.client).get) match {
          case Some(client) => client foreach { c =>
            clients ! Clients.Stop(c)
          }
          case None => logger.warn(s"No client actor to stop!")
        }
    })
  }

  private def emitToChannel(channel: Channel): ClientEmit = in => {
    if (in == ipc.ClientIn.Disconnect) terminateConnection(channel)
    else channel.writeAndFlush(new TextWebSocketFrame(in.write))
  }

  // cancel before the handshake was completed
  private def sendSimpleErrorResponse(channel: Channel, status: HttpResponseStatus): ChannelFuture = {
    val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status)
    response.headers.set(HttpHeaderNames.CONNECTION, "close")
    response.headers.set(HttpHeaderNames.CONTENT_LENGTH, "0")
    val f = channel.write(response)
    f.addListener(ChannelFutureListener.CLOSE)
    f
  }

  // nicely terminate an ongoing connection
  private def terminateConnection(channel: Channel): ChannelFuture =
    channel.writeAndFlush(new CloseWebSocketFrame).addListener(ChannelFutureListener.CLOSE)

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = cause match {
    // IO exceptions happen all the time, it usually just means that the client has closed the connection before fully
    // sending/receiving the response.
    case e: IOException => ctx.channel.close()
    case e: WebSocketHandshakeException => ctx.channel.close()
    case e: CorruptedWebSocketFrameException => // Max frame length of 2048 has been exceeded.
      logger.info("CorruptedWebSocketFrameException ", e)
      // just ignore it I guess
    case e: TooLongFrameException =>
      logger.info("TooLongFrameException", e)
      sendSimpleErrorResponse(ctx.channel, HttpResponseStatus.REQUEST_URI_TOO_LONG)
    case e: IllegalArgumentException if Option(e.getMessage).exists(_.contains("Header value contains a prohibited character")) =>
      sendSimpleErrorResponse(ctx.channel, HttpResponseStatus.BAD_REQUEST)
    case e =>
      logger.error("Exception in Netty", e)
      super.exceptionCaught(ctx, cause)
  }
}

private object ProtocolHandler {

  private val logger = Logger(getClass)

  object key {
    val client = AttributeKey.valueOf[Future[Client]]("client")
    val limit = AttributeKey.valueOf[RateLimit]("limit")
  }
}
