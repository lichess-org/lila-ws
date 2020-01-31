package lila.ws
package ipc

import chess.format.{ FEN, Uci, UciCharPair }
import chess.opening.FullOpening
import chess.variant.Crazyhouse
import play.api.libs.json._

import lila.ws.util.LilaJsObject.augment

sealed trait ClientIn extends ClientMsg {
  def write: String
}

object ClientIn {

  import Chess.json._

  case object Pong extends ClientIn {
    val write = "0"
  }

  case object Resync extends ClientIn {
    val write = cliMsg("resync")
  }

  // triggers actual disconnection
  case object Disconnect extends ClientIn {
    val write = cliMsg("bye") // not actually sent
  }

  case class LobbyPong(members: Int, rounds: Int) extends ClientIn {
    val write = Json stringify Json.obj(
      "t" -> "n",
      "d" -> members,
      "r" -> rounds
    )
  }

  case class Fen(gameId: Game.Id, lastUci: Uci, fen: FEN) extends ClientIn {
    def write =
      cliMsg(
        "fen",
        Json.obj(
          "id"  -> gameId.value,
          "lm"  -> lastUci,
          "fen" -> fen
        )
      )
  }

  case class Mlat(millis: Double) extends ClientIn {
    lazy val write = cliMsg("mlat", millis)
  }

  case class NbMembers(value: Int) extends ClientIn {
    lazy val write = cliMsg("member/nb", value)
  }

  case class NbRounds(value: Int) extends ClientIn {
    lazy val write = cliMsg("round/nb", value)
  }

  sealed trait HasVersion extends ClientMsg {
    val version: SocketVersion
  }

  case class Versioned(json: JsonString, version: SocketVersion, troll: IsTroll) extends HasVersion {
    lazy val full = Payload(JsonString(s"""{"v":$version,${json.value drop 1}"""))
    lazy val skip = Payload(JsonString(s"""{"v":$version}"""))
  }

  case class Payload(json: JsonString) extends ClientIn {
    def write = json.value
  }
  def payload(js: JsValue)                 = Payload(JsonString(Json stringify js))
  def payload(tpe: String, js: JsonString) = Payload(JsonString(cliMsg(tpe, js)))

  case class Crowd(doc: JsObject) extends ClientIn {
    lazy val write = cliMsg("crowd", doc)
  }
  val emptyCrowd = Crowd(Json.obj())

  case class LobbyPairing(fullId: Game.FullId) extends ClientIn {
    def write =
      cliMsg(
        "redirect",
        Json.obj(
          "id"  -> fullId.value,
          "url" -> s"/$fullId"
        )
      )
  }

  case class LobbyNonIdle(payload: Payload) extends ClientIn {
    def write = payload.write
  }

  case class OnlyFor(endpoint: OnlyFor.Endpoint, payload: Payload) extends ClientMsg {
    def write = payload.write
  }
  object OnlyFor {
    sealed trait Endpoint
    case object Lobby           extends Endpoint
    case class Room(id: RoomId) extends Endpoint
  }
  def onlyFor(select: OnlyFor.type => OnlyFor.Endpoint, payload: Payload) =
    OnlyFor(select(OnlyFor), payload)

  case class TourReminder(tourId: Tour.ID, tourName: String) extends ClientIn {
    lazy val write = cliMsg(
      "tournamentReminder",
      Json.obj(
        "id"   -> tourId,
        "name" -> tourName
      )
    )
  }

  def tvSelect(data: JsonString) = payload("tvSelect", data)

  case class Opening(path: Path, opening: FullOpening) extends ClientIn {
    def write =
      cliMsg(
        "opening",
        Json.obj(
          "path"    -> path,
          "opening" -> opening
        )
      )
  }

  case object StepFailure extends ClientIn {
    def write = cliMsg("stepFailure")
  }

  case class Node(
      path: Path,
      id: UciCharPair,
      ply: Int,
      move: chess.format.Uci.WithSan,
      fen: chess.format.FEN,
      check: Boolean,
      dests: Map[chess.Pos, List[chess.Pos]],
      opening: Option[chess.opening.FullOpening],
      drops: Option[List[chess.Pos]],
      crazyData: Option[Crazyhouse.Data],
      chapterId: Option[ChapterId]
  ) extends ClientIn {
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
              .add("check" -> check)
              .add("drops" -> drops.map { drops =>
                JsString(drops.map(_.key).mkString)
              })
              .add("crazy" -> crazyData)
          )
          .add("ch" -> chapterId)
      )
  }

  case class Dests(
      path: Path,
      dests: String,
      opening: Option[chess.opening.FullOpening],
      chapterId: Option[ChapterId]
  ) extends ClientIn {
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
  }

  case class Ack(id: Option[Int]) extends ClientIn {
    def write = id.fold(cliMsg("ack")) { cliMsg("ack", _) }
  }

  case class RoundResyncPlayer(playerId: Game.PlayerId) extends ClientIn {
    def write = cliMsg("resync")
  }
  case class RoundGone(playerId: Game.PlayerId, v: Boolean) extends ClientIn {
    def write = cliMsg("gone", v)
  }
  case class RoundGoneIn(playerId: Game.PlayerId, seconds: Int) extends ClientIn {
    def write = cliMsg("goneIn", seconds)
  }
  case class RoundVersioned(
      version: SocketVersion,
      flags: RoundEventFlags,
      tpe: String,
      data: JsonString
  ) extends HasVersion {
    val full         = Payload(JsonString(cliMsg(tpe, data, version)))
    lazy val skip    = Payload(JsonString(s"""{"v":$version}"""))
    lazy val noDests = Payload(JsonString(destsRemover.replaceAllIn(full.write, "")))
  }
  def roundTourStanding(data: JsonString) = payload("tourStanding", data)

  case class Palantir(userIds: Iterable[User.ID]) extends ClientIn {
    def write = cliMsg("palantir", userIds)
  }

  case class MsgType(orig: User.ID) extends ClientIn {
    def write = cliMsg("msgType", orig)
  }

  private val destsRemover = ""","dests":\{[^\}]+}""".r

  private def cliMsg[A: Writes](t: String, data: A): String = Json stringify Json.obj(
    "t" -> t,
    "d" -> data
  )
  private def cliMsg(t: String, data: JsonString): String = s"""{"t":"$t","d":${data.value}}"""
  private def cliMsg(t: String, data: JsonString, version: SocketVersion): String =
    s"""{"t":"$t","v":$version,"d":${data.value}}"""
  private def cliMsg(t: String, int: Int): String      = s"""{"t":"$t","d":$int}"""
  private def cliMsg(t: String, bool: Boolean): String = s"""{"t":"$t","d":$bool}"""
  private def cliMsg(t: String): String                = s"""{"t":"$t"}"""
}
