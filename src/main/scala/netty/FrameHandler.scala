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
            ctx.channel
              .writeAndFlush(new CloseWebSocketFrame(1008, "rate limit exceeded"))
              .addListener(_ => ctx.channel.close())
          else
            ClientOut parse txt foreach:

              case ClientOut.Switch(uri) if endpoint != null =>
                withClientOf(ctx.channel) { connector.switch(_, endpoint, ctx.channel)(uri) }

              case ClientOut.Unexpected(msg) =>
                Monitor.clientOutUnexpected.increment()
                logger.info(s"Unexpected $msg")

              case ClientOut.WrongHole =>
                Monitor.clientOutWrongHole.increment()

              case out => withClientOf(ctx.channel)(_ tell out)
      case frame: PongWebSocketFrame =>
        val lagMillis = (System.currentTimeMillis() - frame.content().getLong(0)).toInt
        val pong      = ClientOut.RoundPongFrame(lagMillis)
        Option(ctx.channel.attr(key.client).get) foreach { _.value foreach { _ foreach (_ ! pong) } }
      case frame =>
        logger.info("unsupported frame type: " + frame.getClass.getName)

  private def withClientOf(channel: Channel)(f: Client => Unit): Unit =
    Option(channel.attr(key.client).get) match
      case Some(clientFu) =>
        clientFu.value match
          case Some(client) => client foreach f
          case None         => clientFu foreach f
      case None => logger.warn(s"No client actor on channel $channel")

private object FrameHandler:

  private val logger = Logger(getClass)
