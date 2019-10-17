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
    val write = Json stringify Json.obj("t" -> "resync")
  }

  case class LobbyPong(members: Int, rounds: Int) extends ClientIn {
    val write = Json stringify Json.obj(
      "t" -> "n",
      "d" -> members,
      "r" -> rounds
    )
  }

  case class Fen(game: Game.ID, lastUci: Uci, fen: FEN) extends ClientIn {
    def write = clientMsg("fen", Json.obj(
      "id" -> game,
      "lm" -> lastUci,
      "fen" -> fen
    ))
  }

  case class Mlat(millis: Double) extends ClientIn {
    lazy val write = clientMsg("mlat", millis)
  }

  case class NbMembers(value: Int) extends ClientIn {
    lazy val write = clientMsg("member/nb", value)
  }

  case class NbRounds(value: Int) extends ClientIn {
    lazy val write = clientMsg("round/nb", value)
  }

  case class Versioned(json: JsonString, version: SocketVersion, troll: IsTroll) extends ClientMsg {
    lazy val full = Payload(JsonString(s"""{"v":$version,${json.value drop 1}"""))
    lazy val skip = Payload(JsonString(s"""{"v":$version}"""))
  }

  case class Payload(json: JsonString) extends ClientIn {
    def write = json.value
  }

  case class Crowd(doc: JsObject) extends ClientIn {
    lazy val write = clientMsg("crowd", doc)
  }
  val emptyCrowd = Crowd(Json.obj())

  case class LobbyNonIdle(payload: Payload) extends ClientIn {
    def write = payload.write
  }

  case class OnlyFor(endpoint: OnlyFor.Endpoint, payload: Payload) extends ClientIn {
    def write = payload.write
  }
  object OnlyFor {
    sealed trait Endpoint
    case object Site extends Endpoint
    case object Lobby extends Endpoint
    case class Room(id: String) extends Endpoint
  }
  def onlyFor(select: OnlyFor.type => OnlyFor.Endpoint, payload: Payload) = OnlyFor(select(OnlyFor), payload)

  case class Opening(path: Path, opening: FullOpening) extends ClientIn {
    def write = clientMsg("opening", Json.obj(
      "path" -> path,
      "opening" -> opening
    ))
  }

  case object StepFailure extends ClientIn {
    def write = clientMsg("stepFailure")
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
    def write = clientMsg("node", Json.obj(
      "path" -> path,
      "node" -> Json.obj(
        "ply" -> ply,
        "fen" -> fen,
        "id" -> id,
        "uci" -> move.uci,
        "san" -> move.san,
        "dests" -> dests,
        "children" -> JsArray()
      ).add("opening" -> opening)
        .add("check" -> check)
        .add("drops", drops.map { drops =>
          JsString(drops.map(_.key).mkString)
        })
        .add("crazy", crazyData)
    )
      .add("ch", chapterId))
  }

  case class Dests(
      path: Path,
      dests: String,
      opening: Option[chess.opening.FullOpening],
      chapterId: Option[ChapterId]
  ) extends ClientIn {
    def write = clientMsg("dests", Json.obj(
      "dests" -> dests,
      "path" -> path
    ).add("opening" -> opening)
      .add("ch", chapterId))
  }

  private def clientMsg[A: Writes](t: String, data: A): String = Json stringify Json.obj(
    "t" -> t,
    "d" -> data
  )

  private def clientMsg(t: String, data: JsonString): String =
    s"""{"t":"$t","d":${data.value}}"""

  private def clientMsg(t: String) = Json stringify Json.obj(
    "t" -> t
  )
}
