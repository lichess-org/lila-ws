package lila.ws
package ipc

import chess.format.{ FEN, Uci }
import java.lang.Double.parseDouble
import java.lang.Integer.parseInt
import play.api.libs.json._
import scala.util.Try

sealed trait LilaOut

object LilaOut {

  case class Move(game: Game.ID, lastUci: Uci, fen: FEN) extends LilaOut

  case class Mlat(millis: Double) extends LilaOut

  case class TellFlag(flag: String, json: JsObject) extends LilaOut

  case class TellUser(user: User.ID, json: JsObject) extends LilaOut

  case class TellSri(sri: Sri, json: JsObject) extends LilaOut

  case class TellAll(json: JsObject) extends LilaOut

  case class DisconnectUser(user: User.ID) extends LilaOut

  def read(str: String): Option[LilaOut] = {
    val parts = str.split(" ", 2)
    val args = parts.lift(1) getOrElse ""
    parts(0) match {

      case "move" => args.split(" ", 3) match {
        case Array(game, lastUci, fen) => Uci(lastUci) map { Move(game, _, FEN(fen)) }
        case _ => None
      }

      case "mlat" => Try(Mlat(parseDouble(args))).toOption

      case "tell/flag" => args.split(" ", 2) match {
        case Array(flag, jsonStr) => Json.parse(jsonStr).asOpt[JsObject] map { TellFlag(flag, _) }
        case _ => None
      }

      case "tell/user" => args.split(" ", 2) match {
        case Array(user, jsonStr) => Json.parse(jsonStr).asOpt[JsObject] map { TellFlag(user, _) }
        case _ => None
      }

      case "tell/sri" => args.split(" ", 2) match {
        case Array(sri, jsonStr) => Json.parse(jsonStr).asOpt[JsObject] map { TellSri(Sri(sri), _) }
        case _ => None
      }

      case "tell/all" => Json.parse(args).asOpt[JsObject] map TellAll.apply

      case "disconnect/user" => Some(DisconnectUser(args))

      case _ => None
    }
  }
}
