package lila.ws
package ipc

import chess.format.{ Fen, Uci, UciPath }
import chess.variant.Variant
import chess.{ Centis, Color, Square }
import play.api.libs.json.*
import scala.util.{ Success, Try }
import cats.data.NonEmptyList

sealed trait ClientOut extends ClientMsg

sealed trait ClientOutSite  extends ClientOut
sealed trait ClientOutLobby extends ClientOut
sealed trait ClientOutStudy extends ClientOut
sealed trait ClientOutRound extends ClientOut
sealed trait ClientOutRacer extends ClientOut

object ClientOut:

  case class Ping(lag: Option[Int]) extends ClientOutSite

  case class Watch(ids: Set[Game.Id]) extends ClientOutSite
  case object StartWatchingTvChannels extends ClientOutSite

  case object MoveLat extends ClientOutSite

  case object Notified extends ClientOutSite

  case object FollowingOnline extends ClientOutSite

  case class Opening(variant: Variant, path: UciPath, fen: Fen.Epd) extends ClientOutSite

  case class AnaMove(
      orig: Square,
      dest: Square,
      fen: Fen.Epd,
      path: UciPath,
      variant: Variant,
      chapterId: Option[ChapterId],
      promotion: Option[chess.PromotableRole],
      payload: JsObject
  ) extends ClientOutSite

  case class AnaDrop(
      role: chess.Role,
      square: Square,
      fen: Fen.Epd,
      path: UciPath,
      variant: Variant,
      chapterId: Option[ChapterId],
      payload: JsObject
  ) extends ClientOutSite

  case class AnaDests(
      fen: Fen.Epd,
      path: UciPath,
      variant: Variant,
      chapterId: Option[ChapterId]
  ) extends ClientOutSite

  case class EvalGet(
      fen: Fen.Epd,
      variant: Variant,
      multiPv: MultiPv,
      path: UciPath,
      up: Boolean
  ) extends ClientOutSite

  case class EvalPut(
      fen: Fen.Epd,
      variant: Variant,
      pvs: NonEmptyList[evalCache.EvalCacheEntry.Pv],
      knodes: evalCache.Knodes,
      depth: Depth
  ) extends ClientOutSite

  case class EvalGetMulti(fens: List[Fen.Epd], variant: Variant) extends ClientOutSite

  case class MsgType(dest: User.Id) extends ClientOutSite

  case class SiteForward(payload: JsObject) extends ClientOutSite

  case class UserForward(payload: JsObject) extends ClientOutSite

  case class Unexpected(msg: JsValue) extends ClientOutSite

  case object WrongHole extends ClientOutSite

  case object Ignore extends ClientOutSite

  case class Switch(uri: lila.ws.util.RequestUri) extends ClientOutSite

  // lobby

  case class Idle(value: Boolean, payload: JsValue) extends ClientOutLobby
  case class LobbyJoin(payload: JsValue)            extends ClientOutLobby
  case class LobbyForward(payload: JsValue)         extends ClientOutLobby

  // study

  case class StudyForward(payload: JsValue) extends ClientOutStudy

  // round

  case class RoundPlayerForward(payload: JsValue) extends ClientOutRound
  case class RoundMove(uci: Uci, blur: Boolean, lag: ClientMoveMetrics, ackId: Option[Int])
      extends ClientOutRound
  case class RoundHold(mean: Int, sd: Int)    extends ClientOutRound
  case class RoundBerserk(ackId: Option[Int]) extends ClientOutRound
  case class RoundSelfReport(name: String)    extends ClientOutRound
  case class RoundFlag(color: Color)          extends ClientOutRound
  case object RoundBye                        extends ClientOutRound
  case class RoundPongFrame(lagMillis: Int)   extends ClientOutRound

  // chat

  case class ChatSay(msg: String)                                        extends ClientOut
  case class ChatTimeout(suspect: User.Id, reason: String, text: String) extends ClientOut

  // challenge

  case object ChallengePing extends ClientOut

  // palantir

  case object PalantirPing extends ClientOut

  // storm

  case class StormKey(key: String, pad: String) extends ClientOutSite

  // racer

  case class RacerScore(score: Int) extends ClientOutRacer
  case object RacerJoin             extends ClientOutRacer
  case object RacerStart            extends ClientOutRacer

  // impl

  def parse(str: String): Try[ClientOut] =
    if str == "p" || str == "null" || str == """{"t":"p"}""" then emptyPing
    else
      Try(Json parse str) map:
        case o: JsObject =>
          o str "t" flatMap {
            case "p" => Some(Ping(o int "l"))
            case "startWatching" =>
              o str "d" map { d =>
                Watch(Game.Id from d.split(" ", 17).take(16).toSet)
              } orElse Some(Ignore) // old apps send empty watch lists
            case "startWatchingTvChannels" => Some(StartWatchingTvChannels)
            case "moveLat"                 => Some(MoveLat)
            case "notified"                => Some(Notified)
            case "following_onlines"       => Some(FollowingOnline)
            case "opening" =>
              for
                d    <- o obj "d"
                path <- d.get[UciPath]("path")
                fen  <- d.get[Fen.Epd]("fen")
                variant = dataVariant(d)
              yield Opening(variant, path, fen)
            case "anaMove" =>
              for
                d    <- o obj "d"
                orig <- d str "orig" flatMap { Square.fromKey(_) }
                dest <- d str "dest" flatMap { Square.fromKey(_) }
                path <- d.get[UciPath]("path")
                fen  <- d.get[Fen.Epd]("fen")
                variant   = dataVariant(d)
                chapterId = d.get[ChapterId]("ch")
                promotion = d str "promotion" flatMap { chess.Role.promotable(_) }
              yield AnaMove(orig, dest, fen, path, variant, chapterId, promotion, o)
            case "anaDrop" =>
              for
                d      <- o obj "d"
                role   <- d str "role" flatMap chess.Role.allByName.get
                square <- d str "pos" flatMap { Square.fromKey(_) }
                path   <- d.get[UciPath]("path")
                fen    <- d.get[Fen.Epd]("fen")
                variant   = dataVariant(d)
                chapterId = d.get[ChapterId]("ch")
              yield AnaDrop(role, square, fen, path, variant, chapterId, o)
            case "anaDests" =>
              for
                d    <- o obj "d"
                path <- d.get[UciPath]("path")
                fen  <- d.get[Fen.Epd]("fen")
                variant   = dataVariant(d)
                chapterId = d.get[ChapterId]("ch")
              yield AnaDests(fen, path, variant, chapterId)
            case "evalGet"             => o.obj("d").flatMap(evalCache.EvalCacheJsonHandlers.readGet)
            case "evalPut"             => o.obj("d").flatMap(evalCache.EvalCacheJsonHandlers.readPut)
            case "evalGetMulti"        => o.obj("d").flatMap(evalCache.EvalCacheJsonHandlers.readGetMulti)
            case "msgType"             => o.get[User.Id]("d") map MsgType.apply
            case "msgSend" | "msgRead" => Some(UserForward(o))
            // lobby
            case "idle" => o boolean "d" map { Idle(_, o) }
            case "join" => Some(LobbyJoin(o))
            case "cancel" | "joinSeek" | "cancelSeek" | "poolIn" | "poolOut" | "hookIn" | "hookOut" =>
              Some(LobbyForward(o))
            // study
            case "like" | "setPath" | "deleteNode" | "promote" | "forceVariation" | "setRole" | "kick" |
                "leave" | "shapes" | "addChapter" | "setChapter" | "editChapter" | "descStudy" |
                "descChapter" | "deleteChapter" | "clearAnnotations" | "sortChapters" | "editStudy" |
                "setTag" | "setComment" | "deleteComment" | "setGamebook" | "toggleGlyph" | "explorerGame" |
                "requestAnalysis" | "invite" | "relaySync" | "setTopics" | "clearVariations" =>
              Some(StudyForward(o))
            // round
            case "move" =>
              for
                d    <- o obj "d"
                move <- d str "u" flatMap Uci.Move.apply orElse parseOldMove(d)
                blur  = d int "b" contains 1
                ackId = d int "a"
              yield RoundMove(move, blur, parseMetrics(d), ackId)
            case "drop" =>
              for
                d      <- o obj "d"
                role   <- d str "role"
                square <- d str "pos"
                drop   <- Uci.Drop.fromStrings(role, square)
                blur  = d int "b" contains 1
                ackId = d int "a"
              yield RoundMove(drop, blur, parseMetrics(d), ackId)
            case "hold" =>
              for
                d    <- o obj "d"
                mean <- d int "mean"
                sd   <- d int "sd"
              yield RoundHold(mean, sd)
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
              for
                data   <- o obj "d"
                userId <- data.get[User.Id]("userId")
                reason <- data str "reason"
                text   <- data str "text"
              yield ChatTimeout(userId, reason, text)
            case "ping" => Some(ChallengePing)
            // storm
            case "sk1" =>
              o str "d" flatMap { s =>
                s split '!' match
                  case Array(key, pad) => Some(StormKey(key, pad))
                  case _               => None
              }
            // racer
            case "racerScore" => o int "d" map RacerScore.apply
            case "racerJoin"  => Some(RacerJoin)
            case "racerStart" => Some(RacerStart)

            case "switch" =>
              for
                data <- o obj "d"
                uri  <- data.get[lila.ws.util.RequestUri]("uri")
              yield Switch(uri)
            case "wrongHole" => Some(WrongHole)
            case _           => None
          } getOrElse Unexpected(o)
        case js => Unexpected(js)

  private val emptyPing: Try[ClientOut] = Success(Ping(None))

  private def dataVariant(d: JsObject): Variant =
    Variant.orDefault(d.get[Variant.LilaKey]("variant"))

  private def parseOldMove(d: JsObject) = for
    orig <- d str "from"
    dest <- d str "to"
    prom = d str "promotion"
    move <- Uci.Move.fromStrings(orig, dest, prom)
  yield move

  private def parseMetrics(d: JsObject) =
    ClientMoveMetrics(
      d.int("l") map { Centis.ofMillis(_) },
      d.str("s") flatMap { v =>
        Try(Centis(Integer.parseInt(v, 36))).toOption
      }
    )
