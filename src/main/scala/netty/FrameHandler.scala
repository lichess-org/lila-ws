package lila.ws
package netty

import com.typesafe.scalalogging.Logger
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import scala.concurrent.ExecutionContext

import ipc.ClientOut

final private class FrameHandler(implicit ec: ExecutionContext)
    extends SimpleChannelInboundHandler[WebSocketFrame] {

  import FrameHandler._
  import ProtocolHandler.key

  override protected def channelRead0(
      ctx: ChannelHandlerContext,
      anyFrame: WebSocketFrame
  ) =
    anyFrame match {
      case frame: TextWebSocketFrame =>
        val txt = frame.text
        if (txt.nonEmpty) {
          val limiter = ctx.channel.attr(key.limit).get
          if (limiter == null || limiter(txt)) ClientOut parse txt foreach {

            case ClientOut.Unexpected(msg) =>
              Monitor.clientOutUnexpected.increment()
              logger.info(s"Unexpected $msg")

            case ClientOut.WrongHole =>
              Monitor.clientOutWrongHole.increment()

            case out =>
              Option(ctx.channel.attr(key.client).get) match {
                case Some(clientFu) =>
                  clientFu.value match {
                    case Some(client) => client foreach (_ ! out)
                    case None         => clientFu foreach (_ ! out)
                  }
                case None => logger.warn(s"No client actor to receive $out")
              }
          }
        }
      case frame =>
        logger.info("unsupported frame type: " + frame.getClass.getName)
    }
}

private object FrameHandler {

  private val logger = Logger(getClass)
}
