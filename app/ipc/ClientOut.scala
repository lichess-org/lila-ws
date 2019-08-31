package lila.ws
package ipc

import play.api.libs.json._
import chess.format.FEN
import chess.variant.Variant

sealed trait ClientOut

object ClientOut {

  case class Ping(lag: Option[Int]) extends ClientOut

  case class Watch(ids: Set[Game.ID]) extends ClientOut

  case object MoveLat extends ClientOut

  case object Notified extends ClientOut

  case object FollowingOnline extends ClientOut

  case class Opening(variant: Variant, path: String, fen: FEN) extends ClientOut

  implicit val jsonRead = Reads[ClientOut] { js =>
    (js match {
      case JsNull => Some(Ping(None))
      case o: JsObject => (o \ "t").asOpt[String] flatMap {
        case "p" => Some(Ping((o \ "l").asOpt[Int]))
        case "startWatching" => (o \ "d").asOpt[String].map(_ split " " toSet) map Watch.apply
        case "moveLat" => Some(MoveLat)
        case "notified" => Some(Notified)
        case "following_online" => Some(Notified)
        case "opening" => for {
          path <- (o \ "path").asOpt[String]
          fen <- (o \ "fen").asOpt[String]
          variant = Variant.orDefault((o \ "variant").asOpt[String] getOrElse "")
        } yield Opening(variant, path, FEN(fen))
        case _ => None
      }
      case _ => None
    }) map { JsSuccess(_) } getOrElse JsError(s"Invalid ClientOut $js")
  }
}
