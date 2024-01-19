package lila.ws
package netty

import com.typesafe.scalalogging.Logger
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.websocketx.*

import ipc.ClientOut

final private class FrameHandler(connector: ActorChannelConnector)(using Executor)
    extends SimpleChannelInboundHandler[WebSocketFrame]:

  import FrameHandler.*
  import ProtocolHandler.key

  override protected def channelRead0(
      ctx: ChannelHandlerContext,
      anyFrame: WebSocketFrame
  ) =
    anyFrame match
      case frame: TextWebSocketFrame =>
        val txt = frame.text
        if txt.nonEmpty then
          val endpoint = ctx.channel.attr(key.endpoint).get
          if endpoint != null && !endpoint.rateLimit(txt) then
            shutdown(ctx.channel, 1008, "rate limit exceeded")
          else
            ClientOut parse txt foreach:

              case ClientOut.Unexpected(msg) =>
                Monitor.clientOutUnexpected.increment()
                logger.info(s"Unexpected $msg")
                shutdown(ctx.channel, 1011, "unexpected message")

              case ClientOut.WrongHole =>
                Monitor.clientOutWrongHole.increment()
              // choice of hole is a personal decision

              case out => withClientOf(ctx.channel)(_ tell out)

      case frame: PongWebSocketFrame =>
        val lagMillis = (System.currentTimeMillis() - frame.content().getLong(0)).toInt
        val pong      = ClientOut.RoundPongFrame(lagMillis)
        Option(ctx.channel.attr(key.client).get) foreach { _.value foreach { _ foreach (_ ! pong) } }

      case frame =>
        logger.info("unsupported frame type: " + frame.getClass.getName)
        shutdown(ctx.channel, 1003, s"${frame.getClass.getName} unsupported")

  private def withClientOf(channel: Channel)(f: Client => Unit): Unit =
    Option(channel.attr(key.client).get) match
      case Some(clientFu) =>
        clientFu.value match
          case Some(client) => client foreach f
          case None         => clientFu foreach f
      case None =>
        logger.warn(s"No client actor on channel $channel")
        shutdown(channel, 1011, s"no actor on $channel")

  private def shutdown(channel: Channel, code: Int, reason: String): Unit =
    channel.writeAndFlush(CloseWebSocketFrame(code, reason)).addListener(_ => channel.close())

private object FrameHandler:

  private val logger = Logger(getClass)
