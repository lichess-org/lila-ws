package controllers

import javax.inject._

import akka.actor._
import akka.event.Logging
import akka.stream.Materializer
import play.api.libs.streams.ActorFlow
import play.api.Logger
import play.api.mvc._
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }

import lichess.ws._

@Singleton
class SocketController @Inject() (val controllerComponents: ControllerComponents)(implicit
    actorSystem: ActorSystem,
    mat: Materializer,
    executionContext: ExecutionContext
) extends BaseController {

  private type WSMessage = JsValue
  private val logger = Logger(getClass)

  def site(): WebSocket =
    WebSocket.accept[WSMessage, WSMessage] { req =>
      ActorFlow actorRef { out => SiteClientActor.props(out) }
    }
}
