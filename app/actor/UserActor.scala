package lila.ws

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import play.api.libs.json.JsObject

import ipc.{ LilaIn, ClientIn }

object UserActor {

  def empty(lilaIn: LilaIn => Unit) = apply(Map.empty, lilaIn)

  def apply(users: Map[User.ID, Set[ActorRef[Any]]], lilaIn: LilaIn => Unit): Behavior[Input] = Behaviors.receiveMessage {

    case Connect(user, client) => apply(
      users + (user.id -> (users get user.id match {
        case None =>
          lilaIn(LilaIn.Connect(user))
          Set(client)
        case Some(clients) =>
          clients + client
      })),
      lilaIn
    )

    case Disconnect(user, client) => apply(
      users get user.id match {
        case None => users
        case Some(clients) =>
          val newClients = clients - client
          if (newClients.isEmpty) {
            lilaIn(LilaIn.Disconnect(user))
            users - user.id
          }
          else users + (user.id -> newClients)
      },
      lilaIn
    )

    case TellOne(userId, payload) =>
      users get userId foreach {
        _ foreach { _ ! payload }
      }
      Behavior.same

    case TellMany(userIds, payload) =>
      userIds flatMap users.get foreach {
        _ foreach { _ ! payload }
      }
      Behavior.same
  }

  sealed trait Input
  case class Connect(user: User, client: ActorRef[Any]) extends Input
  case class Disconnect(user: User, client: ActorRef[Any]) extends Input
  case class TellOne(userId: User.ID, payload: Any) extends Input
  case class TellMany(userIds: Iterable[User.ID], payload: Any) extends Input
}
