package lila.ws
package netty

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
    extends SimpleChannelInboundHandler[FullHttpRequest](false):

  override def channelRead0(ctx: ChannelHandlerContext, req: FullHttpRequest): Unit =
    router(new util.RequestHeader(req.uri, req.headers)) foreach {
      case Left(status) =>
        sendErrorResponse(
          ctx,
          new DefaultFullHttpResponse(req.protocolVersion(), status, ctx.alloc().buffer(0))
        )
        req.release()
      case Right(_) if req.method != HttpMethod.GET =>
        val response = new DefaultFullHttpResponse(
          req.protocolVersion(),
          HttpResponseStatus.METHOD_NOT_ALLOWED,
          ctx.alloc().buffer(0)
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
