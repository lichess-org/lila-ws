package lila.ws

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.Behaviors

import ipc.LilaIn

object LagActor {

  def empty(lilaIn: LilaIn => Unit) = apply(Map.empty, lilaIn)

  private def apply(lags: Map[User.ID, Int], lilaIn: LilaIn => Unit): Behavior[Input] = Behaviors.receiveMessage {

    case Set(user, lag) => apply(lags + (user.id -> lag), lilaIn)

    case Publish =>
      lilaIn(LilaIn.Lags(lags.toMap))
      apply(Map.empty, lilaIn)
  }

  sealed trait Input
  case class Set(user: User, lag: Int) extends Input
  case object Publish extends Input
}
