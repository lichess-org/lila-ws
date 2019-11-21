package lila.ws
package netty

import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import com.typesafe.scalalogging.Logger
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import scala.concurrent.ExecutionContext

private final class FrameHandler(
    clients: ActorRef[Clients.Control]
)(implicit ec: ExecutionContext) extends SimpleChannelInboundHandler[WebSocketFrame] {

  import FrameHandler._

  override protected def channelRead0(
    ctx: ChannelHandlerContext,
    anyFrame: WebSocketFrame
  ) = anyFrame match {
    case frame: TextWebSocketFrame =>
      val txt = frame.text
      if (txt.nonEmpty) {
        // TODO ratelimit
        ipc.ClientOut parse frame.text() foreach { out =>
          Option(ctx.channel.attr(Clients.attrKey).get) match {
            case Some(client) => client foreach (_ ! out)
            case None => logger.warn(s"No client actor to receive $out")
          }
        }
      }
    case frame =>
      logger.info("unsupported frame type: " + frame.getClass().getName())
  }
}

private object FrameHandler {

  private val logger = Logger(getClass)
}
