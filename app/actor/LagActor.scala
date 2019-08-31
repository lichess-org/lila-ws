package lila.ws

import akka.actor._

import ipc.LilaIn

final class LagActor(lilaIn: LilaIn => Unit) extends Actor {

  import LagActor._

  val lags = collection.mutable.Map[User.ID, Int]()

  def receive = {

    case Set(user, lag) => lags += (user.id -> lag)

    case Publish =>
      lilaIn(LilaIn.Lags(lags.toMap))
      lags.clear
  }
}

object LagActor {

  case class Set(user: User, lag: Int)
  case object Publish

  def props(lilaIn: LilaIn => Unit) = Props(new LagActor(lilaIn))
}
