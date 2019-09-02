package controllers

import akka.stream.scaladsl._
import javax.inject._
import play.api.http.websocket._
import play.api.libs.json._
import play.api.mvc._
import play.api.mvc.WebSocket.MessageFlowTransformer
import scala.concurrent.{ ExecutionContext, Future }

import lila.ws._
import lila.ws.ipc.{ ClientIn, ClientOut }
import lila.ws.util.Util.flagOf

@Singleton
class SocketController @Inject() (val controllerComponents: ControllerComponents)(implicit
    ec: ExecutionContext,
    siteServer: SiteServer
) extends BaseController {

  def site(sri: String): WebSocket = WebSocket { req =>
    siteServer.connect(req, Sri(sri), flagOf(req)) map Right.apply
  }
}
