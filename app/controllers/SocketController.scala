package controllers

import javax.inject._
import play.api.mvc._
import scala.concurrent.ExecutionContext

import lila.ws._
import lila.ws.util.Util.flagOf

@Singleton
class SocketController @Inject() (
    server: Server,
    val controllerComponents: ControllerComponents
)(implicit ec: ExecutionContext) extends BaseController {

  def site(sri: String, apiVersion: Int): WebSocket = WebSocket { req =>
    server.connectToSite(req, Sri(sri), flagOf(req)) map Right.apply
  }

  def lobby(sri: String, apiVersion: Int): WebSocket = WebSocket { req =>
    server.connectToLobby(req, Sri(sri), flagOf(req)) map Right.apply
  }
}
