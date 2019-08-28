package lichess.ws

import akka.actor._

final class CountActor extends Actor {

  import CountActor._

  var count = 0

  def receive = {
    case Connect => count += 1
    case Disconnect => count -= 1
    case Get => sender ! count
  }
}

object CountActor {

  case object Connect
  case object Disconnect
  case object Get

  def props = Props(new CountActor)
}
