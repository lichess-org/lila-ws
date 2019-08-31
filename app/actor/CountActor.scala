package lila.ws

import akka.actor._

import ipc.LilaIn

final class CountActor(lilaIn: LilaIn => Unit) extends Actor {

  import CountActor._

  var count = 0

  def receive = {
    case Connect => count += 1
    case Disconnect => count -= 1
    case Publish => lilaIn(LilaIn.Connections(count))
  }
}

object CountActor {

  case object Connect
  case object Disconnect
  case object Publish
}
