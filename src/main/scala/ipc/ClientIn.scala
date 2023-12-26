package lila.ws
package ipc

import chess.{ Check, Color, Ply }
import chess.format.{ EpdFen, Uci, UciCharPair, UciPath }
import chess.opening.Opening
import chess.variant.Crazyhouse
import chess.bitboard.Bitboard
import play.api.libs.json.*

sealed trait ClientIn extends ClientMsg:
  def write: String

object ClientIn:

  import Chess.json.given

  case object Pong extends ClientIn:
    val write = "0"

  case object Resync extends ClientIn:
    val write = cliMsg("resync")

  // triggers actual disconnection
  case object Disconnect extends ClientIn:
    val write = cliMsg("bye") // not actually sent

  case class LobbyPong(members: Int, rounds: Int) extends ClientIn:
    val write = Json stringify Json.obj(
      "t" -> "n",
      "d" -> members,
      "r" -> rounds
    )

  case class Fen(gameId: Game.Id, position: Position) extends ClientIn:
    def write =
      cliMsg(
        "fen",
        Json
          .obj(
            "id"  -> gameId.value,
            "lm"  -> position.lastUci,
            "fen" -> position.fenWithColor
          )
          .add("wc" -> position.clock.map(_.white))
          .add("bc" -> position.clock.map(_.black))
      )

  case class Finish(gameId: Game.Id, winner: Option[Color]) extends ClientIn:
    def write = cliMsg("finish", Json.obj("id" -> gameId.value, "win" -> winner.map(_.letter.toString)))

  case class Mlat(millis: Double) extends ClientIn:
    lazy val write = cliMsg("mlat", millis)

  sealed trait HasVersion extends ClientMsg:
    val version: SocketVersion

  case class Versioned(json: JsonString, version: SocketVersion, troll: IsTroll) extends HasVersion:
    lazy val full = Payload(JsonString(s"""{"v":$version,${json.value drop 1}"""))
    lazy val skip = Payload(JsonString(s"""{"v":$version}"""))

  case class Payload(json: JsonString) extends ClientIn:
    def write = json.value
  def payload(js: JsValue)                 = Payload(JsonString(Json stringify js))
  def payload(tpe: String, js: JsonString) = Payload(JsonString(cliMsg(tpe, js)))

  case class Crowd(doc: JsObject) extends ClientIn:
    lazy val write = cliMsg("crowd", doc)
  val emptyCrowd = Crowd(Json.obj())

  case class LobbyPairing(fullId: Game.FullId) extends ClientIn:
    def write =
      cliMsg(
        "redirect",
        Json.obj(
          "id"  -> fullId.value,
          "url" -> s"/$fullId"
        )
      )

  case class LobbyNonIdle(payload: Payload) extends ClientIn:
    def write = payload.write

  case class OnlyFor(endpoint: OnlyFor.Endpoint, payload: Payload) extends ClientMsg:
    def write = payload.write
  object OnlyFor:
    enum Endpoint:
      case Api
      case Lobby
      case Room(id: RoomId)
  def onlyFor(select: OnlyFor.Endpoint.type => OnlyFor.Endpoint, payload: Payload) =
    OnlyFor(select(OnlyFor.Endpoint), payload)

  case class TourReminder(tourId: Tour.Id, tourName: String) extends ClientIn:
    lazy val write = cliMsg(
      "tournamentReminder",
      Json.obj(
        "id"   -> tourId,
        "name" -> tourName
      )
    )

  def tvSelect(data: JsonString) = payload("tvSelect", data)

  case class OpeningMsg(path: UciPath, opening: Opening) extends ClientIn:
    def write =
      cliMsg(
        "opening",
        Json.obj(
          "path"    -> path,
          "opening" -> opening
        )
      )

  case object StepFailure extends ClientIn:
    def write = cliMsg("stepFailure")

  case class Node(
      path: UciPath,
      id: UciCharPair,
      ply: Ply,
      move: Uci.WithSan,
      fen: EpdFen,
      check: Check,
      dests: Map[chess.Square, Bitboard],
      opening: Option[Opening],
      drops: Option[List[chess.Square]],
      crazyData: Option[Crazyhouse.Data],
      chapterId: Option[ChapterId]
  ) extends ClientIn:
    def write =
      cliMsg(
        "node",
        Json
          .obj(
            "path" -> path,
            "node" -> Json
              .obj(
                "ply"      -> ply,
                "fen"      -> fen,
                "id"       -> id,
                "uci"      -> move.uci,
                "san"      -> move.san,
                "dests"    -> dests,
                "children" -> JsArray()
              )
              .add("opening" -> opening)
              .add("check" -> check.yes)
              .add("drops" -> drops.map { drops =>
                JsString(drops.map(_.key).mkString)
              })
              .add("crazy" -> crazyData)
          )
          .add("ch" -> chapterId)
      )

  case class Dests(
      path: UciPath,
      dests: String,
      opening: Option[Opening],
      chapterId: Option[ChapterId]
  ) extends ClientIn:
    def write =
      cliMsg(
        "dests",
        Json
          .obj(
            "dests" -> dests,
            "path"  -> path
          )
          .add("opening" -> opening)
          .add("ch" -> chapterId)
      )

  case class Ack(id: Option[Int]) extends ClientIn:
    def write = id.fold(cliMsg("ack")) { cliMsg("ack", _) }

  case class RoundResyncPlayer(playerId: Game.PlayerId) extends ClientIn:
    def write = cliMsg("resync")
  case class RoundGone(playerId: Game.PlayerId, v: Boolean) extends ClientIn:
    def write = cliMsg("gone", v)
  case class RoundGoneIn(playerId: Game.PlayerId, seconds: Int) extends ClientIn:
    def write = cliMsg("goneIn", seconds)
  case class RoundVersioned(
      version: SocketVersion,
      flags: RoundEventFlags,
      tpe: String,
      data: JsonString
  ) extends HasVersion:
    val full         = Payload(JsonString(cliMsg(tpe, data, version)))
    lazy val skip    = Payload(JsonString(s"""{"v":$version}"""))
    lazy val noDests = Payload(JsonString(destsRemover.replaceAllIn(full.write, "")))
  case object RoundPingFrameNoFlush extends ClientIn:
    val write = "" // not actually sent
  def roundTourStanding(data: JsonString) = payload("tourStanding", data)

  def roundFull(data: JsonString) = payload("full", data)

  case class Palantir(userIds: Iterable[User.Id]) extends ClientIn:
    def write = cliMsg("palantir", userIds)

  case class MsgType(orig: User.Id) extends ClientIn:
    def write = cliMsg("msgType", orig)

  object following:

    case class Onlines(users: List[FriendList.UserView]) extends ClientIn:
      def write =
        Json stringify Json.obj(
          "t"       -> "following_onlines",
          "d"       -> users.map(_.data.titleName),
          "playing" -> users.collect { case u if u.meta.playing => u.id },
          "patrons" -> users.collect { case u if u.data.patron.yes => u.id }
        )
    case class Enters(user: FriendList.UserView) extends ClientIn:
      // We use 'd' for backward compatibility with the mobile client
      def write = Json.stringify:
        Json
          .obj(
            "t" -> "following_enters",
            "d" -> user.data.titleName
          )
          .add("patron" -> user.data.patron.yes)

    abstract class Event(key: String) extends ClientIn:
      def user: User.Id
      def write = cliMsg(s"following_$key", user)
    case class Leaves(user: User.Id)         extends Event("leaves")
    case class Playing(user: User.Id)        extends Event("playing")
    case class StoppedPlaying(user: User.Id) extends Event("stopped_playing")

  case class StormKey(signed: String) extends ClientIn:
    def write = cliMsg("sk1", signed)

  case class EvalHit(data: JsObject) extends ClientIn:
    val write = cliMsg("evalHit", data)

  case class EvalHitMulti(data: JsObject) extends ClientIn:
    val write = cliMsg("evalHitMulti", data)

  def racerState(data: JsonString) = payload("racerState", data)

  case class SwitchResponse(uri: lila.ws.util.RequestUri, status: Int) extends ClientIn:
    def write = cliMsg("switch", Json.obj("uri" -> uri, "status" -> status))

  private val destsRemover = ""","dests":\{[^\}]+}""".r

  private def cliMsg[A: Writes](t: String, data: A): String = cliMsg(t, Json.toJson(data))
  private def cliMsg(t: String, js: JsValue): String        = s"""{"t":"$t","d":${Json stringify js}}"""
  private def cliMsg(t: String, data: JsonString): String   = s"""{"t":"$t","d":${data.value}}"""
  private def cliMsg(t: String, data: JsonString, version: SocketVersion): String =
    s"""{"t":"$t","v":$version,"d":${data.value}}"""
  private def cliMsg(t: String, int: Int): String      = s"""{"t":"$t","d":$int}"""
  private def cliMsg(t: String, bool: Boolean): String = s"""{"t":"$t","d":$bool}"""
  private def cliMsg(t: String): String                = s"""{"t":"$t"}"""
