package controllers

import javax.inject._

import akka.actor._
import akka.event.Logging
import play.api.libs.json._
import play.api.Logger
import play.api.mvc._

import scala.concurrent.{ ExecutionContext, Future }

import lichess.ws._

@Singleton
class SocketController @Inject() (val controllerComponents: ControllerComponents)(implicit
    ec: ExecutionContext,
    system: ActorSystem,
    siteServer: SiteServer
) extends BaseController {

  private type WSMessage = JsValue
  private val logger = Logger(getClass)

  def site(sri: String): WebSocket =
    WebSocket.acceptOrResult[WSMessage, WSMessage] { req =>
      siteServer.connect(req, Sri(sri)) map Right.apply
    }
}
