package lila.ws
package ipc

import chess.format.{ FEN, Uci }
import chess.variant.Variant
import chess.{ Centis, Color, MoveMetrics, Pos }
import play.api.libs.json._
import scala.util.{ Success, Try }

import lila.ws.util.LilaJsObject.augment

sealed trait ClientOut extends ClientMsg

sealed trait ClientOutSite  extends ClientOut
sealed trait ClientOutLobby extends ClientOut
sealed trait ClientOutStudy extends ClientOut
sealed trait ClientOutRound extends ClientOut

object ClientOut {

  case class Ping(lag: Option[Int]) extends ClientOutSite

  case class Watch(ids: Set[Game.Id]) extends ClientOutSite

  case object MoveLat extends ClientOutSite

  case object Notified extends ClientOutSite

  case object FollowingOnline extends ClientOutSite

  case class Opening(variant: Variant, path: Path, fen: FEN) extends ClientOutSite

  case class AnaMove(
      orig: Pos,
      dest: Pos,
      fen: FEN,
      path: Path,
      variant: Variant,
      chapterId: Option[ChapterId],
      promotion: Option[chess.PromotableRole],
      payload: JsObject
  ) extends ClientOutSite

  case class AnaDrop(
      role: chess.Role,
      pos: Pos,
      fen: FEN,
      path: Path,
      variant: Variant,
      chapterId: Option[ChapterId],
      payload: JsObject
  ) extends ClientOutSite

  case class AnaDests(
      fen: FEN,
      path: Path,
      variant: Variant,
      chapterId: Option[ChapterId]
  ) extends ClientOutSite

  case class MsgType(dest: User.ID) extends ClientOutSite

  case class SiteForward(payload: JsObject) extends ClientOutSite

  case class UserForward(payload: JsObject) extends ClientOutSite

  case class Unexpected(msg: JsValue) extends ClientOutSite

  case object WrongHole extends ClientOutSite

  case object Ignore extends ClientOutSite

  // lobby

  case class Idle(value: Boolean, payload: JsValue) extends ClientOutLobby
  case class LobbyForward(payload: JsValue)         extends ClientOutLobby

  // study

  case class StudyForward(payload: JsValue) extends ClientOutStudy

  // round

  case class RoundPlayerForward(payload: JsValue)                                     extends ClientOutRound
  case class RoundMove(uci: Uci, blur: Boolean, lag: MoveMetrics, ackId: Option[Int]) extends ClientOutRound
  case class RoundHold(mean: Int, sd: Int)                                            extends ClientOutRound
  case class RoundBerserk(ackId: Option[Int])                                         extends ClientOutRound
  case class RoundSelfReport(name: String)                                            extends ClientOutRound
  case class RoundFlag(color: Color)                                                  extends ClientOutRound
  case object RoundBye                                                                extends ClientOutRound

  // chat

  case class ChatSay(msg: String)                                       extends ClientOut
  case class ChatTimeout(suspect: String, reason: String, text: String) extends ClientOut

  // challenge

  case object ChallengePing extends ClientOut

  // palantir

  case object PalantirPing extends ClientOut

  // impl

  def parse(str: String): Try[ClientOut] =
    if (str == "null" || str == """{"t":"p"}""") emptyPing
    else
      Try(Json parse str) map {
        case o: JsObject =>
          o str "t" flatMap {
            case "p" => Some(Ping(o int "l"))
            case "startWatching" =>
              o str "d" map { d =>
                Watch(d.split(" ").take(16).map(Game.Id.apply).toSet)
              } orElse Some(Ignore) // old apps send empty watch lists
            case "moveLat"           => Some(MoveLat)
            case "notified"          => Some(Notified)
            case "following_onlines" => Some(FollowingOnline)
            case "opening" =>
              for {
                d    <- o obj "d"
                path <- d str "path"
                fen  <- d str "fen"
                variant = dataVariant(d)
              } yield Opening(variant, Path(path), FEN(fen))
            case "anaMove" =>
              for {
                d    <- o obj "d"
                orig <- d str "orig" flatMap Pos.fromKey
                dest <- d str "dest" flatMap Pos.fromKey
                path <- d str "path"
                fen  <- d str "fen"
                variant   = dataVariant(d)
                chapterId = d str "ch" map ChapterId.apply
                promotion = d str "promotion" flatMap chess.Role.promotable
              } yield AnaMove(orig, dest, FEN(fen), Path(path), variant, chapterId, promotion, o)
            case "anaDrop" =>
              for {
                d    <- o obj "d"
                role <- d str "role" flatMap chess.Role.allByName.get
                pos  <- d str "pos" flatMap Pos.fromKey
                path <- d str "path"
                fen  <- d str "fen"
                variant   = dataVariant(d)
                chapterId = d str "ch" map ChapterId.apply
              } yield AnaDrop(role, pos, FEN(fen), Path(path), variant, chapterId, o)
            case "anaDests" =>
              for {
                d    <- o obj "d"
                path <- d str "path"
                fen  <- d str "fen"
                variant   = dataVariant(d)
                chapterId = d str "ch" map ChapterId.apply
              } yield AnaDests(FEN(fen), Path(path), variant, chapterId)
            case "evalGet" | "evalPut" => Some(SiteForward(o))
            case "msgType"             => o str "d" map MsgType.apply
            case "msgSend" | "msgRead" => Some(UserForward(o))
            // lobby
            case "idle" => o boolean "d" map { Idle(_, o) }
            case "join" | "cancel" | "joinSeek" | "cancelSeek" | "poolIn" | "poolOut" | "hookIn" |
                "hookOut" =>
              Some(LobbyForward(o))
            // study
            case "like" | "setPath" | "deleteNode" | "promote" | "forceVariation" | "setRole" | "kick" |
                "leave" | "shapes" | "addChapter" | "setChapter" | "editChapter" | "descStudy" |
                "descChapter" | "deleteChapter" | "clearAnnotations" | "sortChapters" | "editStudy" |
                "setTag" | "setComment" | "deleteComment" | "setGamebook" | "toggleGlyph" | "explorerGame" |
                "requestAnalysis" | "invite" | "relaySync" | "setTopics" =>
              Some(StudyForward(o))
            // round
            case "move" =>
              for {
                d    <- o obj "d"
                move <- d str "u" flatMap Uci.Move.apply orElse parseOldMove(d)
                blur  = d int "b" contains 1
                ackId = d int "a"
              } yield RoundMove(move, blur, parseLag(d), ackId)
            case "drop" =>
              for {
                d    <- o obj "d"
                role <- d str "role"
                pos  <- d str "pos"
                drop <- Uci.Drop.fromStrings(role, pos)
                blur  = d int "b" contains 1
                ackId = d int "a"
              } yield RoundMove(drop, blur, parseLag(d), ackId)
            case "hold" =>
              for {
                d    <- o obj "d"
                mean <- d int "mean"
                sd   <- d int "sd"
              } yield RoundHold(mean, sd)
            case "berserk"      => Some(RoundBerserk(o obj "d" flatMap (_ int "a")))
            case "rep"          => o obj "d" flatMap (_ str "n") map RoundSelfReport.apply
            case "flag"         => o str "d" flatMap Color.fromName map RoundFlag.apply
            case "bye2"         => Some(RoundBye)
            case "palantirPing" => Some(PalantirPing)
            case "moretime" | "rematch-yes" | "rematch-no" | "takeback-yes" | "takeback-no" | "draw-yes" |
                "draw-no" | "draw-claim" | "resign" | "resign-force" | "draw-force" | "abort" | "outoftime" =>
              Some(RoundPlayerForward(o))
            // chat
            case "talk" => o str "d" map { ChatSay.apply }
            case "timeout" =>
              for {
                data   <- o obj "d"
                userId <- data str "userId"
                reason <- data str "reason"
                text   <- data str "text"
              } yield ChatTimeout(userId, reason, text)
            case "ping"      => Some(ChallengePing)
            case "wrongHole" => Some(WrongHole)
            case _           => None
          } getOrElse Unexpected(o)
        case js => Unexpected(js)
      }

  private val emptyPing: Try[ClientOut] = Success(Ping(None))

  private def dataVariant(d: JsObject): Variant = Variant.orDefault(d str "variant" getOrElse "")

  private def parseOldMove(d: JsObject) =
    for {
      orig <- d str "from"
      dest <- d str "to"
      prom = d str "promotion"
      move <- Uci.Move.fromStrings(orig, dest, prom)
    } yield move

  private def parseLag(d: JsObject) =
    MoveMetrics(
      d.int("l") orElse d.int("lag") map Centis.ofMillis,
      d.str("s") flatMap { v =>
        Try(Centis(Integer.parseInt(v, 36))).toOption
      }
    )
}
