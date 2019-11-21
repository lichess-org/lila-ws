package lila.ws

import akka.actor.typed.{ ActorSystem, Behavior }
import io.netty.channel.Channel
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import javax.inject._
import scala.concurrent.{ Future, ExecutionContext }

import util.RequestHeader

@Singleton
final class Router @Inject() (
    controller: Controller,
    system: ActorSystem[Clients.Control]
)(implicit ec: ExecutionContext) {

  // implicit def ec = system.executionContext

  def apply(
    uri: String,
    headers: HttpHeaders,
    channel: Channel,
    ip: IpAddress
  ): Controller.Response = {
    Monitor.count.client.inc
    val req = new RequestHeader(uri, headers, ip)
    val emit = emitToChannel(channel)
    req.path drop 1 split '/' match {
      case Array("socket", _) => controller.site(req, emit)
      case _ => Future successful Left(HttpResponseStatus.NOT_FOUND)
    }
  }

  private def emitToChannel(channel: Channel): ClientEmit =
    in => {
      channel.writeAndFlush(new TextWebSocketFrame(in.write))
      Monitor.count.clientIn.inc
    }
}
