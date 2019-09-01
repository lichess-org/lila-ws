package controllers

import javax.inject._

import play.api.libs.json._
import play.api.mvc._
import play.api.mvc.WebSocket.MessageFlowTransformer

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal

import lila.ws._
import lila.ws.ipc.{ ClientIn, ClientOut }

@Singleton
class SocketController @Inject() (val controllerComponents: ControllerComponents)(implicit
    ec: ExecutionContext,
    siteServer: SiteServer
) extends BaseController {

  private implicit val ClientFlowTransformer: MessageFlowTransformer[ClientOut, ClientIn] = {

    import play.api.http.websocket._
    import play.api.libs.streams.AkkaStreams
    import akka.stream.scaladsl._

    def closeOnException[T](block: => T) = try {
      Left(block)
    }
    catch {
      case NonFatal(e) => Right(CloseMessage(Some(CloseCodes.Unacceptable), "Unable to parse json message"))
    }

    new MessageFlowTransformer[ClientOut, ClientIn] {
      def transform(flow: Flow[ClientOut, ClientIn, _]) = {
        AkkaStreams.bypassWith[Message, ClientOut, Message](Flow[Message] collect {
          case TextMessage(text) => closeOnException {
            ClientOut.jsonRead(Json parse text)
          }
        })(flow map { out => TextMessage(out.write) })
      }
    }
  }

  def site(sri: String): WebSocket =
    WebSocket.acceptOrResult[ClientOut, ClientIn] { req =>
      val flag = req.target getQueryParameter "flag" flatMap Flag.make
      siteServer.connect(req, Sri(sri), flag) map Right.apply
    }
}
