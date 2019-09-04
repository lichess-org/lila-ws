package lila.ws
package ipc

import play.api.libs.json._

sealed trait LilaIn extends LilaMsg {
  def write: String
}

object LilaIn {

  case class TellSri(sri: Sri, userId: Option[User.ID], payload: JsValue) extends LilaIn {
    def write = s"tell/sri ${sri.value} ${userId getOrElse "-"} ${Json.stringify(payload)}"
  }

  case class Watch(id: Game.ID) extends LilaIn {
    def write = s"watch $id"
  }

  case class Unwatch(id: Game.ID) extends LilaIn {
    def write = s"unwatch $id"
  }

  case class Notified(userId: User.ID) extends LilaIn {
    def write = s"notified $userId"
  }

  case class Friends(userId: User.ID) extends LilaIn {
    def write = s"friends $userId"
  }

  case class Lags(value: Map[User.ID, Int]) extends LilaIn {
    def write = s"lags ${value.map { case (user, lag) => s"$user:$lag" } mkString ","}"
  }

  case class Connections(count: Int) extends LilaIn {
    def write = s"connections $count"
  }

  case class ConnectUser(user: User) extends LilaIn {
    def write = s"connect/user ${user.id}"
  }

  case class DisconnectUsers(userIds: Set[User.ID]) extends LilaIn {
    def write = s"disconnect/users ${userIds mkString ","}"
  }

  case object DisconnectAll extends LilaIn {
    def write = "disconnect/all"
  }

  sealed trait LobbyIn

  case class ConnectSri(sri: Sri, userId: Option[User.ID]) extends LilaIn with LobbyIn {
    def write = s"connect/sri $sri${userId.fold("")(" " + _)}"
  }

  case class DisconnectSri(sri: Sri) extends LilaIn with LobbyIn {
    def write = s"disconnect/sri $sri"
  }
}
