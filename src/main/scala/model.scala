package lila.ws

import chess.{ ByColor, Color }
import chess.format.{ Fen, Uci }
import ornicar.scalalib.SecureRandom

object User:
  opaque type Id = String
  object Id extends OpaqueString[Id]
  opaque type Name = String
  object Name extends OpaqueString[Name]
  opaque type Title = String
  object Title extends OpaqueString[Title]
  opaque type TitleName = String
  object TitleName extends OpaqueString[TitleName]:
    def apply(name: Name, title: Option[Title]): TitleName =
      TitleName(title.fold(name.value)(_.value + " " + name.value))
  opaque type Patron = Boolean
  object Patron extends YesNo[Patron]

opaque type RoomId = String
object RoomId extends OpaqueString[RoomId]:
  def ofPlayer(id: Game.FullId): RoomId = id.gameId.value

object Game:

  opaque type Id = String
  object Id extends OpaqueString[Id]:
    extension (id: Id) def full(playerId: PlayerId): FullId = FullId(s"$id$playerId")

  opaque type FullId = String
  object FullId extends OpaqueString[FullId]:
    extension (fullId: FullId)
      def gameId   = Game.Id(fullId.value take 8)
      def playerId = PlayerId(fullId.value drop 8)

  opaque type AnyId = String
  object AnyId extends OpaqueString[AnyId]

  opaque type PlayerId = String
  object PlayerId extends OpaqueString[PlayerId]

  case class Player(id: PlayerId, userId: Option[User.Id])

  // must only contain invariant data (no status, turns, or termination)
  // because it's cached in Mongo.scala
  case class Round(id: Game.Id, players: ByColor[Player], ext: Option[RoundExt]):
    def player(id: PlayerId, userId: Option[User.Id]): Option[RoundPlayer] =
      players.zipColor.collect:
        case (c, p) if p.id == id && p.userId == userId => RoundPlayer(id, c, ext)

  case class RoundPlayer(id: PlayerId, color: Color, ext: Option[RoundExt]):
    def tourId    = ext collect { case RoundExt.InTour(id) => id }
    def swissId   = ext collect { case RoundExt.InSwiss(id) => id }
    def simulId   = ext collect { case RoundExt.InSimul(id) => id }
    def extRoomId = simulId.map(_ into RoomId) orElse swissId.map(_ into RoomId)

  enum RoundExt:
    case InTour(id: Tour.Id)   extends RoundExt
    case InSwiss(id: Swiss.Id) extends RoundExt
    case InSimul(id: Simul.Id) extends RoundExt
end Game

object Simul:
  opaque type Id = String
  object Id extends OpaqueString[Id]

object Tour:
  opaque type Id = String
  object Id extends OpaqueString[Id]

object Study:
  opaque type Id = String
  object Id extends OpaqueString[Id]

object Swiss:
  opaque type Id = String
  object Id extends OpaqueString[Id]

object Team:
  opaque type Id = String
  object Id extends OpaqueString[Id]

  opaque type HasChat = Boolean
  object HasChat extends YesNo[HasChat]

  enum Access(val id: Int):
    case None    extends Access(0)
    case Leaders extends Access(10)
    case Members extends Access(20)

object Challenge:
  opaque type Id = String
  object Id extends OpaqueString[Id]
  enum Challenger:
    case Anon(secret: String)
    case User(userId: lila.ws.User.Id)
    case Open

object Racer:
  opaque type Id = String
  object Id extends OpaqueString[Id]
  enum PlayerId(val key: String):
    case User(user: lila.ws.User.Id) extends PlayerId(user.value)
    case Anon(sid: String)           extends PlayerId(s"@$sid")

opaque type Sri = String
object Sri extends OpaqueString[Sri]:
  def random                         = Sri(SecureRandom nextString 12)
  def from(str: String): Option[Sri] = if str contains ' ' then None else Some(str)

opaque type Flag = String
object Flag extends OpaqueString[Flag]:
  def make(value: String) =
    value match
      case "simul" | "tournament" | "api" => Some(Flag(value))
      case _                              => None
  val api = Flag("api")

opaque type IpAddress = String
object IpAddress extends OpaqueString[IpAddress]

opaque type ChapterId = String
object ChapterId extends OpaqueString[ChapterId]

opaque type JsonString = String
object JsonString extends OpaqueString[JsonString]

opaque type SocketVersion = Int
object SocketVersion extends OpaqueInt[SocketVersion]

opaque type IsTroll = Boolean
object IsTroll extends YesNo[IsTroll]

case class UptimeMillis(millis: Long) extends AnyVal:
  def toNow: Long = UptimeMillis.make.millis - millis

object UptimeMillis:
  def make = UptimeMillis(System.currentTimeMillis() - startedAtMillis)

case class ThroughStudyDoor(user: User.Id, through: Either[RoomId, RoomId])

case class RoundEventFlags(
    watcher: Boolean,
    owner: Boolean,
    player: Option[chess.Color],
    moveBy: Option[chess.Color],
    troll: Boolean
)

opaque type UserTv = String
object UserTv extends OpaqueString[UserTv]

case class Clock(white: Int, black: Int)
case class Position(lastUci: Uci, fen: Fen.Board, clock: Option[Clock], turnColor: Color):
  def fenWithColor = fen.andColor(turnColor)

opaque type MultiPv = Int
object MultiPv extends OpaqueInt[MultiPv]

opaque type Depth = Int
object Depth extends OpaqueInt[Depth]
