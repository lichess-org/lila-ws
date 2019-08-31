package lila.ws

import akka.actor._
import scala.collection.mutable.AnyRefMap
import play.api.libs.json.JsObject

import ipc.{ LilaIn, ClientIn }

final class UserActor(lilaIn: LilaIn => Unit) extends Actor {

  import UserActor._

  val users = AnyRefMap.empty[User.ID, Set[ActorRef]]

  def receive = {

    case Connect(user) => users.put(user.id, users.get(user.id) match {
      case None =>
        lilaIn(LilaIn.Connect(user))
        Set(sender)
      case Some(clients) =>
        clients + sender
    })

    case Disconnect(user) => users get user.id match {
      case None =>
      case Some(clients) =>
        val newClients = clients - sender
        if (newClients.isEmpty) {
          lilaIn(LilaIn.Disconnect(user))
          users remove user.id
        }
        else users.put(user.id, newClients)
    }

    case TellOne(userId, payload) => users get userId foreach {
      _ foreach { _ ! payload }
    }

    case TellMany(userIds, payload) => userIds flatMap users.get foreach {
      _ foreach { _ ! payload }
    }
  }
}

object UserActor {

  case class Connect(user: User)
  case class Disconnect(user: User)
  case class TellOne(userId: User.ID, payload: Any)
  case class TellMany(userIds: Iterable[User.ID], payload: Any)

  def props(lilaIn: LilaIn => Unit) = Props(new UserActor(lilaIn))
}
