package lila.ws

trait StringValue extends Any {
  def value: String
  override def toString = value
}
trait IntValue extends Any {
  def value: Int
  override def toString = value.toString
}

case class User(id: User.ID) extends AnyVal

object User {
  type ID = String
}

object Game {
  case class Id(value: String) extends AnyVal with StringValue {
    def full(playerId: PlayerId) = FullId(s"$value{$playerId.value}")
  }
  case class FullId(value: String) extends AnyVal with StringValue {
    def gameId = Id(value take 8)
    def playerId = PlayerId(value drop 8)
  }
  case class PlayerId(value: String) extends AnyVal with StringValue
}

object Simul {
  type ID = String
}

case class Simul(id: Simul.ID) extends AnyVal

object Tour {
  type ID = String
}

case class Tour(id: Tour.ID) extends AnyVal

object Study {
  type ID = String
}

case class Study(id: Study.ID) extends AnyVal

object Chat {
  type ID = String
}

case class Chat(id: Chat.ID) extends AnyVal

case class Sri(value: String) extends AnyVal with StringValue

object Sri {
  type Str = String
  def random = Sri(util.Util.random string 12)
  def from(str: String): Option[Sri] =
    if (str contains ' ') None
    else Some(Sri(str))
}

case class Flag private (value: String) extends AnyVal with StringValue

object Flag {
  def make(value: String) = value match {
    case "simul" | "tournament" | "api" => Some(Flag(value))
    case _ => None
  }
  val api = Flag("api")
}

case class Path(value: String) extends AnyVal with StringValue

case class ChapterId(value: String) extends AnyVal with StringValue

case class JsonString(value: String) extends AnyVal with StringValue

case class SocketVersion(value: Int) extends AnyVal with IntValue with Ordered[SocketVersion] {
  def compare(other: SocketVersion) = Integer.compare(value, other.value)
}

case class IsTroll(value: Boolean) extends AnyVal

case class RoomId(value: String) extends AnyVal with StringValue

case class UserLag(userId: User.ID, lag: Int)

case class ReqId(value: Int) extends AnyVal with IntValue

case class ThroughStudyDoor(user: User, through: Either[RoomId, RoomId])
