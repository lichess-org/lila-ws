package lila.ws
package netty

import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import scala.concurrent.ExecutionContext

private final class FrameHandler(
    clients: ActorRef[Clients.Control]
)(implicit ec: ExecutionContext) extends SimpleChannelInboundHandler[WebSocketFrame] {

  override protected def channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame) = frame match {
    case frame: TextWebSocketFrame =>
      val txt = frame.text
      if (txt.nonEmpty) {
        // TODO ratelimit
        ipc.ClientOut parse frame.text() foreach { out =>
          Option(ctx.channel.attr(Clients.attrKey).get) match {
            case Some(client) => client foreach (_ ! out)
            case None => println(s"No client actor to receive $out")
          }
        }
      }
    case frame =>
      throw new UnsupportedOperationException("unsupported frame type: " + frame.getClass().getName())
  }
}
