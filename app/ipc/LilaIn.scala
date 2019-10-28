package lila.ws
package ipc

import play.api.libs.json._

sealed trait LilaIn extends LilaMsg {
  def write: String
}

object LilaIn {

  sealed trait Site extends LilaIn

  sealed trait Lobby extends LilaIn

  sealed trait Room extends LilaIn
  sealed trait Simul extends Room
  sealed trait Tour extends Room
  sealed trait Study extends Room
  sealed trait Round extends Room

  sealed trait AnyRoom extends Simul with Tour with Study with Round

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

  case class KeepAlive(roomId: RoomId) extends AnyRoom {
    def write = s"room/alive $roomId"
  }
  case class KeepAlives(roomIds: Iterable[RoomId]) extends AnyRoom {
    def write = s"room/alives ${commas(roomIds)}"
  }
  case class ChatSay(roomId: RoomId, userId: User.ID, msg: String) extends AnyRoom {
    def write = s"chat/say $roomId $userId $msg"
  }
  case class ChatTimeout(roomId: RoomId, userId: User.ID, suspectId: User.ID, reason: String) extends AnyRoom {
    def write = s"chat/timeout $roomId $userId $suspectId $reason"
  }

  case class TellStudySri(studyId: RoomId, tellSri: TellSri) extends Study {
    import tellSri._
    def write = s"tell/study/sri $studyId $sri ${userId getOrElse "-"} ${Json.stringify(payload)}"
  }

  case class WaitingUsers(roomId: RoomId, name: String, present: Set[User.ID], standby: Set[User.ID]) extends Tour {
    def write = s"tour/waiting $roomId ${commas(present intersect standby)}"
  }

  case class StudyDoor(users: Map[User.ID, Either[RoomId, RoomId]]) extends Study {
    def write = s"study/door ${
      commas(users.map {
        case (u, Right(s)) => s"$u:$s:+"
        case (u, Left(s)) => s"$u:$s:-"
      })
    }"
  }

  case class ReqResponse(reqId: Int, value: String) extends Study {
    def write = s"req/response $reqId $value"
  }

  private def commas(as: Iterable[Any]): String = if (as.isEmpty) "-" else as mkString ","
}
