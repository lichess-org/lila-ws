package lichess.ws

import akka.actor._
import play.api.libs.json._

final class SiteClientActor(
    out: ActorRef,
    sri: Sri,
    user: Option[User],
    actors: Actors
) extends Actor {

  import SiteClientActor._

  val bus = Bus(context.system)

  def prestart() = {
    actors.count ! CountActor.Connect
    bus.subscribe(self, Bus.channel sri sri)
    user foreach { u =>
      bus.subscribe(self, Bus.channel user u.id)
    }
  }

  override def postStop() = {
    actors.count ! CountActor.Disconnect
    bus unsubscribe self
  }

  def receive = {
    case Ping => send(Pong)
    case o: JsObject => (o \ "t").asOpt[String] foreach {
      case "p" =>
        send(Pong)
        for {
          u <- user
          lag <- (o \ "l").asOpt[Int]
        } actors.lag ! LagActor.Set(u, lag)
    }
    case Send(msg) => send(msg)
  }

  def send(msg: JsValue) = out ! msg
}

object SiteClientActor {

  val Ping = JsNull
  val Pong = JsNumber(0)

  case class Send(msg: JsValue)

  def props(
    out: ActorRef,
    sri: Sri,
    user: Option[User],
    actors: Actors
  ) = Props(new SiteClientActor(out, sri, user, actors))
}
