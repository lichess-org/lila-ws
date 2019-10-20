package lila.ws
package ipc

import play.api.libs.json._

sealed trait LilaIn extends LilaMsg {
  def write: String
}

object LilaIn {

  sealed trait Site extends LilaIn

  case class TellSri(sri: Sri, userId: Option[User.ID], payload: JsValue) extends Site with Lobby {
    def write = s"tell/sri ${sri.value} ${userId getOrElse "-"} ${Json.stringify(payload)}"
  }

  case class Watch(id: Game.ID) extends Site {
    def write = s"watch $id"
  }

  case class Unwatch(id: Game.ID) extends Site {
    def write = s"unwatch $id"
  }

  case class Notified(userId: User.ID) extends Site {
    def write = s"notified $userId"
  }
  case class NotifiedBatch(userIds: Iterable[User.ID]) extends Site {
    def write = s"notified/batch ${commas(userIds)}"
  }

  case class Friends(userId: User.ID) extends Site {
    def write = s"friends $userId"
  }
  case class FriendsBatch(userIds: Iterable[User.ID]) extends Site {
    def write = s"friends/batch ${commas(userIds)}"
  }

  case class Lags(value: Map[User.ID, Int]) extends Site {
    def write = s"lags ${commas(value.map { case (user, lag) => s"$user:$lag" })}"
  }

  case class Connections(count: Int) extends Site {
    def write = s"connections $count"
  }

  case class ConnectUser(user: User) extends Site {
    def write = s"connect/user ${user.id}"
  }

  case class DisconnectUsers(userIds: Set[User.ID]) extends Site {
    def write = s"disconnect/users ${commas(userIds)}"
  }

  case object DisconnectAll extends Site {
    def write = "disconnect/all"
  }

  sealed trait Lobby extends LilaIn

  case class ConnectSri(sri: Sri, userId: Option[User.ID]) extends Lobby {
    def write = s"connect/sri $sri${userId.fold("")(" " + _)}"
  }
  type SriUserId = (Sri, Option[User.ID])
  case class ConnectSris(sris: Iterable[SriUserId]) extends Lobby {
    private def render(su: SriUserId) = s"${su._1}${su._2.fold("")(" " + _)}"
    def write = s"connect/sris ${commas(sris map render)}"
  }

  case class DisconnectSri(sri: Sri) extends Lobby {
    def write = s"disconnect/sri $sri"
  }
  case class DisconnectSris(sris: Iterable[Sri]) extends Lobby {
    def write = s"disconnect/sris ${commas(sris)}"
  }

  sealed trait Room extends LilaIn

  case class KeepAlive(roomId: RoomId) extends Room {
    def write = s"room/alive ${roomId.value}"
  }
  case class KeepAlives(roomIds: Iterable[RoomId]) extends Room {
    def write = s"room/alives ${commas(roomIds)}"
  }
  case class ChatSay(roomId: RoomId, userId: User.ID, msg: String) extends Room {
    def write = s"chat/say ${roomId.value} $userId $msg"
  }
  case class ChatTimeout(roomId: RoomId, userId: User.ID, suspectId: User.ID, reason: String) extends Room {
    def write = s"chat/timeout ${roomId.value} $userId $suspectId $reason"
  }
  case class RoomUsers(roomId: RoomId, users: Iterable[User.ID]) extends Room {
    def write = s"room/users ${roomId.value} ${commas(users)}"
  }

  sealed trait Tour extends Room

  private def commas(as: Iterable[Any]): String = if (as.isEmpty) "-" else as mkString ","
}
