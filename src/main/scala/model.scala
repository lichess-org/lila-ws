package lila.ws

import play.api.libs.json.{ Format, Json, Reads, Writes }
import chess.Color
import chess.format.{ FEN, Uci }

trait StringValue extends Any:
  def value: String
  override def toString = value
trait IntValue extends Any:
  def value: Int
  override def toString = value.toString

val stringFormat: Format[String] = Format(Reads.StringReads, Writes.StringWrites)

opaque type UserId = String
object UserId:
  def apply(v: String): UserId = v
extension (o: UserId) def userId = o
given Format[UserId] = stringFormat.bimap(UserId.apply, _.userId)

object Game:
  case class Id(value: String) extends AnyVal with StringValue:
    def full(playerId: PlayerId) = FullId(s"$value$playerId")
  object Id:
    def ofRoom(room: RoomId): Id = Id(room.roomId)
  case class FullId(value: String) extends AnyVal with StringValue:
    def gameId   = Id(value take 8)
    def playerId = PlayerId(value drop 8)
  case class PlayerId(value: String) extends AnyVal with StringValue

  case class Player(id: PlayerId, userId: Option[UserId])

  // must only contain invariant data (no status, turns, or termination)
  // because it's cached in Mongo.scala
  case class Round(id: Id, players: Color.Map[Player], ext: Option[RoundExt]):
    def player(id: PlayerId, userId: Option[UserId]): Option[RoundPlayer] =
      Color.all.collectFirst {
        case c if players(c).id == id && players(c).userId == userId => RoundPlayer(id, c, ext)
      }

  case class RoundPlayer(
      id: PlayerId,
      color: Color,
      ext: Option[RoundExt]
  ):
    def tourId  = ext collect { case RoundExt.Tour(id) => id }
    def swissId = ext collect { case RoundExt.Swiss(id) => id }
    def simulId = ext collect { case RoundExt.Simul(id) => id }

  enum RoundExt(val id: String):
    case Tour(i: String)  extends RoundExt(i)
    case Swiss(i: String) extends RoundExt(i)
    case Simul(i: String) extends RoundExt(i)

object Simul:
  type ID = String

case class Simul(id: Simul.ID) extends AnyVal

object Tour:
  type ID = String

case class Tour(id: Tour.ID) extends AnyVal

object Study:
  type ID = String

case class Study(id: Study.ID) extends AnyVal

object Team:
  type ID = String
  case class View(hasChat: Boolean)
  enum Access(val id: Int):
    case None    extends Access(0)
    case Leaders extends Access(10)
    case Members extends Access(20)

object Swiss:
  type ID = String

object Challenge:
  case class Id(value: String) extends AnyVal with StringValue
  enum Challenger:
    case Anon(secret: String)
    case User(userId: String)
    case Open

object Racer:
  type RaceId = String
  enum PlayerId(val key: String):
    case User(user: UserId) extends PlayerId(user.userId)
    case Anon(sid: String)  extends PlayerId(s"@$sid")

opaque type RoomId = String
object RoomId:
  def apply(v: String): RoomId              = v
  def ofGame(id: Game.Id): RoomId           = id.value
  def ofPlayer(id: Game.FullId): RoomId     = id.gameId.value
  def ofChallenge(id: Challenge.Id): RoomId = id.value
extension (o: RoomId) def roomId = o

opaque type Sri = String
object Sri:
  def apply(v: String): Sri          = v
  def random                         = Sri(util.Util.secureRandom string 12)
  def from(str: String): Option[Sri] = if str contains ' ' then None else Some(str)

case class Flag private (value: String) extends AnyVal with StringValue

object Flag:
  def make(value: String) =
    value match
      case "simul" | "tournament" | "api" => Some(Flag(value))
      case _                              => None
  val api = Flag("api")

case class IpAddress(value: String) extends AnyVal with StringValue

case class Path(value: String) extends AnyVal with StringValue

case class ChapterId(value: String) extends AnyVal with StringValue

opaque type JsonString = String
object JsonString:
  def apply(v: String): JsonString = v
extension (o: JsonString) def jsonString = o

case class SocketVersion(value: Int) extends AnyVal with IntValue with Ordered[SocketVersion]:
  def compare(other: SocketVersion) = Integer.compare(value, other.value)

case class IsTroll(value: Boolean) extends AnyVal

case class UptimeMillis(millis: Long) extends AnyVal:
  def toNow: Long = UptimeMillis.make.millis - millis

object UptimeMillis:
  def make = UptimeMillis(System.currentTimeMillis() - util.Util.startedAtMillis)

case class ThroughStudyDoor(user: UserId, through: Either[RoomId, RoomId])

case class RoundEventFlags(
    watcher: Boolean,
    owner: Boolean,
    player: Option[chess.Color],
    moveBy: Option[chess.Color],
    troll: Boolean
)

opaque type UserTv = String
object UserTv:
  def apply(v: String): UserTv = v
  def from(v: UserId): UserTv  = v
extension (o: UserTv) def tvUserId = o

case class Clock(white: Int, black: Int)
case class Position(lastUci: Uci, fen: FEN, clock: Option[Clock], turnColor: Color):
  def fenWithColor = s"$fen ${turnColor.letter}"
