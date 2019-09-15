package controllers

import javax.inject._
import play.api.Configuration
import play.api.http.HeaderNames
import play.api.http.websocket.Message
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

  def site(sriStr: String, apiVersion: Int): WebSocket = WebSocket { req =>
    CsrfCheck(req) {
      ValidSri(sriStr) { sri =>
        server.connectToSite(req, sri, flagOf(req)) map Right.apply
      }
    }
  }

  def lobby(sriStr: String, apiVersion: Int): WebSocket = WebSocket { req =>
    CsrfCheck(req) {
      ValidSri(sriStr) { sri =>
        server.connectToLobby(req, sri, flagOf(req)) map Right.apply
      }
    }
  }

  def api: WebSocket = WebSocket { req =>
    server.connectToSite(req, Sri.random, Some(Flag.api)) map Right.apply
  }

  private type Response = Future[Either[Result, akka.stream.scaladsl.Flow[Message, Message, _]]]

  private val csrfDomain = config.get[String]("csrf.origin")

  private def CsrfCheck(req: RequestHeader)(f: => Response): Response =
    req.headers get HeaderNames.ORIGIN match {
      case Some(origin) if origin == csrfDomain || origin == "file://" => f
      case _ => Future successful Left(Forbidden("Cross origin request forbidden"))
    }

  private def ValidSri(str: String)(f: Sri => Response): Response =
    Sri from str match {
      case None => Future successful Left(BadRequest("Invalid sri"))
      case Some(validSri) => f(validSri)
    }
}
