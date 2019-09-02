package lila.ws
package ipc

import chess.format.FEN
import chess.Pos
import chess.variant.Variant
import play.api.libs.json._
import scala.util.{ Try, Success }

import lila.ws.util.LilaJsObject.augment

sealed trait ClientOut extends ClientMsg

object ClientOut {

  case class Ping(lag: Option[Int]) extends ClientOut

  case class Watch(ids: Set[Game.ID]) extends ClientOut

  case object MoveLat extends ClientOut

  case object Notified extends ClientOut

  case object FollowingOnline extends ClientOut

  case class Opening(variant: Variant, path: Path, fen: FEN) extends ClientOut

  case class AnaMove(
      orig: Pos,
      dest: Pos,
      fen: FEN,
      path: Path,
      variant: Variant,
      chapterId: Option[ChapterId],
      promotion: Option[chess.PromotableRole]
  ) extends ClientOut

  case class AnaDrop(
      role: chess.Role,
      pos: Pos,
      fen: FEN,
      path: Path,
      variant: Variant,
      chapterId: Option[ChapterId]
  ) extends ClientOut

  case class AnaDests(
      fen: FEN,
      path: Path,
      variant: Variant,
      chapterId: Option[ChapterId]
  ) extends ClientOut

  case class Forward(payload: JsValue) extends ClientOut

  case class Unexpected(msg: JsValue) extends ClientOut

  case object Ignore extends ClientOut

  def parse(str: String): Try[ClientOut] =
    if (str == "null" || str == """{"t":"p"}""") emptyPing
    else Try(Json parse str) map {
      case o: JsObject => o str "t" flatMap {
        case "p" => Some(Ping(o int "l"))
        case "startWatching" => o.str("d").map(_ split " " toSet) map Watch.apply
        case "moveLat" => Some(MoveLat)
        case "notified" => Some(Notified)
        case "following_onlines" => Some(Notified)
        case "opening" => for {
          d <- o obj "d"
          path <- d str "path"
          fen <- d str "fen"
          variant = dataVariant(d)
        } yield Opening(variant, Path(path), FEN(fen))
        case "anaMove" => for {
          d <- o obj "d"
          orig <- d str "orig" flatMap Pos.posAt
          dest <- d str "dest" flatMap Pos.posAt
          path <- d str "path"
          fen <- d str "fen"
          variant = dataVariant(d)
          chapterId = d str "ch" map ChapterId.apply
          promotion = d str "promotion" flatMap chess.Role.promotable
        } yield AnaMove(orig, dest, FEN(fen), Path(path), variant, chapterId, promotion)
        case "anaDrop" => for {
          d <- o obj "d"
          role <- d str "role" flatMap chess.Role.allByName.get
          pos <- d str "pos" flatMap Pos.posAt
          path <- d str "path"
          fen <- d str "fen"
          variant = dataVariant(d)
          chapterId = d str "ch" map ChapterId.apply
        } yield AnaDrop(role, pos, FEN(fen), Path(path), variant, chapterId)
        case "anaDests" => for {
          d <- o obj "d"
          path <- d str "path"
          fen <- d str "fen"
          variant = dataVariant(d)
          chapterId = d str "ch" map ChapterId.apply
        } yield AnaDests(FEN(fen), Path(path), variant, chapterId)
        case "evalGet" | "evalPut" => Some(Forward(o))
        case "ping" => Some(Ignore) // outdated clients
        case _ => None
      } getOrElse Unexpected(o)
      case js => Unexpected(js)
    }

  private val emptyPing: Try[ClientOut] = Success(Ping(None))

  private def dataVariant(d: JsObject): Variant = Variant.orDefault(d str "variant" getOrElse "")
}
