package lila.ws
package netty

import io.netty.buffer.Unpooled
import io.netty.channel.{ ChannelFutureListener, ChannelHandlerContext, SimpleChannelInboundHandler }
import io.netty.handler.codec.http.{
  DefaultFullHttpResponse,
  FullHttpRequest,
  HttpHeaderNames,
  HttpMethod,
  HttpResponseStatus
}
import scala.concurrent.ExecutionContext

final private class RequestHandler(
    router: Router
)(using ec: ExecutionContext)
    extends SimpleChannelInboundHandler[FullHttpRequest](
      false // do not automatically release req
    ):

  override def channelRead0(ctx: ChannelHandlerContext, req: FullHttpRequest): Unit =
    router(new util.RequestHeader(req.uri, req.headers)) foreach {
      case Left(status) =>
        sendErrorResponse(
          ctx,
          new DefaultFullHttpResponse(req.protocolVersion(), status, Unpooled.EMPTY_BUFFER)
        )
        req.release()
      case Right(_) if req.method != HttpMethod.GET =>
        val response = new DefaultFullHttpResponse(
          req.protocolVersion(),
          HttpResponseStatus.METHOD_NOT_ALLOWED,
          Unpooled.EMPTY_BUFFER
        )
        response.headers.set(HttpHeaderNames.ALLOW, "GET")
        sendErrorResponse(ctx, response)
        req.release()
      case Right(endpoint) =>
        // Forward to ProtocolHandler with endpoint attribute
        ctx.channel.attr(ProtocolHandler.key.endpoint).set(endpoint)
        ctx.fireChannelRead(req)
    }

  private def sendErrorResponse(ctx: ChannelHandlerContext, response: DefaultFullHttpResponse) =
    val f = ctx.writeAndFlush(response)
    f.addListener(ChannelFutureListener.CLOSE)
