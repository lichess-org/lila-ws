package lila.ws
package ipc

import chess.format.{ FEN, Uci, UciCharPair }
import chess.opening.FullOpening
import chess.variant.Crazyhouse
import play.api.libs.json._

import lila.ws.util.LilaJsObject.augment

sealed trait ClientIn {
  def write: JsValue
}

object ClientIn {

  import Chess.json._

  case object Pong extends ClientIn {
    val write = JsNumber(0)
  }

  case class Fen(game: Game.ID, lastUci: Uci, fen: FEN) extends ClientIn {
    def write = make("fen", Json.obj(
      "game" -> game,
      "last_uci" -> lastUci,
      "fen" -> fen
    ))
  }

  case class Mlat(millis: Double) extends ClientIn {
    def write = make("mlat", millis)
  }

  case class AnyJson(json: JsObject) extends ClientIn {
    def write = json
  }

  case class Opening(path: Path, opening: FullOpening) extends ClientIn {
    def write = make("opening", Json.obj(
      "path" -> path,
      "opening" -> opening
    ))
  }

  case class Node(
      path: Path,
      id: UciCharPair,
      ply: Int,
      move: chess.format.Uci.WithSan,
      fen: chess.format.FEN,
      check: Boolean,
      dests: Option[Map[chess.Pos, List[chess.Pos]]],
      opening: Option[chess.opening.FullOpening],
      drops: Option[List[chess.Pos]],
      crazyData: Option[Crazyhouse.Data],
      chapterId: Option[ChapterId]
  ) extends ClientIn {
    def write = make("node", Json.obj(
      "path" -> path,
      "node" -> Json.obj(
        "ply" -> ply,
        "fen" -> fen,
        "id" -> id,
        "uci" -> move.uci,
        "san" -> move.san,
        "children" -> JsArray()
      ).add("opening" -> opening)
        .add("check" -> check)
        .add("dests" -> dests)
        .add("drops", drops)
        .add("ch", chapterId)
    ))
  }

  case class Dests(
      path: Path,
      dests: String,
      opening: Option[chess.opening.FullOpening],
      chapterId: Option[ChapterId]
  ) extends ClientIn {
    def write = make("dests", Json.obj(
      "dests" -> dests,
      "path" -> path
    ).add("opening" -> opening)
      .add("ch", chapterId))
  }

  private def make[A: Writes](t: String, data: A) = Json.obj(
    "t" -> t,
    "d" -> data
  )

  implicit val jsonWrite = Writes[ClientIn](_.write)
}
