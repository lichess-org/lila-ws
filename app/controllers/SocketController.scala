package controllers

import javax.inject._

import akka.actor._
import akka.event.Logging
import akka.stream.Materializer
import play.api.libs.json._
import play.api.libs.streams.ActorFlow
import play.api.Logger
import play.api.mvc._

import scala.concurrent.{ ExecutionContext, Future }

import lichess.ws._

@Singleton
class SocketController @Inject() (val controllerComponents: ControllerComponents)(implicit
    executionContext: ExecutionContext,
    actorSystem: ActorSystem,
    mat: Materializer,
    auth: Auth
) extends BaseController {

  private type WSMessage = JsValue
  private val logger = Logger(getClass)

  def site(): WebSocket =
    WebSocket.acceptOrResult[WSMessage, WSMessage] { req =>
      auth(req) map { user =>
        println(user)
        Right(ActorFlow actorRef { out => SiteClientActor.props(out, user) })
      }
    }
}
