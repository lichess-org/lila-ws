package lila.ws
package ipc

import play.api.libs.json._

sealed trait ClientOut

object ClientOut {

  case class Ping(lag: Option[Int]) extends ClientOut

  case class Watch(ids: Set[Game.ID]) extends ClientOut

  case object MoveLat extends ClientOut

  case object Notified extends ClientOut

  case object FollowingOnline extends ClientOut

  implicit val jsonRead = Reads[ClientOut] { js =>
    (js match {
      case JsNull => Some(Ping(None))
      case o: JsObject => (o \ "t").asOpt[String] flatMap {
        case "p" => Some(Ping((o \ "l").asOpt[Int]))
        case "startWatching" => (o \ "d").asOpt[String].map(_ split " " toSet) map Watch.apply
        case "moveLat" => Some(MoveLat)
        case "notified" => Some(Notified)
        case "following_online" => Some(Notified)
        case _ => None
      }
      case _ => None
    }) map { JsSuccess(_) } getOrElse JsError(s"Invalid ClientOut $js")
  }
}
