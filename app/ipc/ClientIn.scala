package lila.ws
package ipc

import play.api.libs.json._

sealed trait ClientIn {
  def write: JsValue
}

object ClientIn {

  case object Pong extends ClientIn {
    val write = JsNumber(0)
  }

  case class Fen(game: Game.ID, lastUci: String, fen: String) extends ClientIn {
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

  private def make[A: Writes](t: String, data: A) = Json.obj(
    "t" -> t,
    "d" -> data
  )

  implicit val jsonWrite = Writes[ClientIn](_.write)
}
