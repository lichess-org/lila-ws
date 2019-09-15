package controllers

import javax.inject._
import play.api.Configuration
import play.api.http.HeaderNames
import play.api.mvc._
import scala.concurrent.{ Future, ExecutionContext }

import lila.ws._
import lila.ws.util.Util.flagOf

@Singleton
class SocketController @Inject() (
    server: Server,
    config: Configuration,
    val controllerComponents: ControllerComponents
)(implicit ec: ExecutionContext) extends BaseController {

  def site(sri: String, apiVersion: Int): WebSocket = WebSocket { req =>
    println(req.headers get HeaderNames.ORIGIN)
    CsrfCheck(req) {
      server.connectToSite(req, Sri(sri), flagOf(req)) map Right.apply
    }
  }

  def lobby(sri: String, apiVersion: Int): WebSocket = WebSocket { req =>
    CsrfCheck(req) {
      server.connectToLobby(req, Sri(sri), flagOf(req)) map Right.apply
    }
  }

  def api: WebSocket = WebSocket { req =>
    server.connectToSite(req, Sri.random, Some(Flag.api)) map Right.apply
  }

  private val csrfDomain = config.get[String]("csrf.origin")

  private def CsrfCheck[R](req: RequestHeader)(f: => Future[Either[Result, R]]): Future[Either[Result, R]] =
    req.headers get HeaderNames.ORIGIN match {
      case Some(origin) if origin == csrfDomain || origin == "file://" => f
      case _ => Future successful Left(Forbidden("Cross origin request forbidden"))
    }
}
