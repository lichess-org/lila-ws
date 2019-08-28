package lichess.ws

import akka.actor._

final class LagActor extends Actor {

  import LagActor._

  val lags = collection.mutable.Map[User.ID, Int]()

  def receive = {
    case Set(user, lag) => lags += (user.id -> lag)
  }
}

object LagActor {

  case class Set(user: User, lag: Int)

  def props = Props(new LagActor)
}
