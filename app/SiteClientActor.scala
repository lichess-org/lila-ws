package lichess.ws

import akka.actor._
import play.api.libs.json._

final class SiteClientActor(out: ActorRef) extends Actor {

  def receive = {
    case Ping => send(Pong)
    case msg: JsValue => send(JsString("Got: " + msg))
  }

  val Ping = JsNull
  val Pong = JsNumber(0)

  def send(msg: JsValue) = out ! msg
}

object SiteClientActor {

  def props(out: ActorRef) = Props(new SiteClientActor(out))
}
