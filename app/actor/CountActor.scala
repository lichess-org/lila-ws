package lila.ws

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.Behavior

import ipc.LilaIn

object CountActor {

  def empty(lilaIn: LilaIn => Unit) = apply(0, lilaIn)

  def apply(count: Int, lilaIn: LilaIn => Unit): Behavior[Input] = Behaviors.receiveMessage {

    case Connect => apply(count + 1, lilaIn)

    case Disconnect => apply(count - 1, lilaIn)

    case Publish =>
      lilaIn(LilaIn.Connections(count))
      Behaviors.same
  }

  sealed trait Input

  case object Connect extends Input
  case object Disconnect extends Input
  case object Publish extends Input
}
