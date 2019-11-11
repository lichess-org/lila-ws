package lila.ws
package ipc

import chess.Color
import chess.format.{ FEN, Uci }
import play.api.libs.json._
import scala.util.Try

import lila.ws.util.Util._

sealed trait LilaOut

sealed trait SiteOut extends LilaOut
sealed trait LobbyOut extends LilaOut
sealed trait RoomOut extends LilaOut
sealed trait SimulOut extends RoomOut
sealed trait TourOut extends RoomOut
sealed trait StudyOut extends RoomOut
sealed trait RoundOut extends RoomOut
sealed trait ChallengeOut extends RoomOut

sealed trait AnyRoomOut extends RoundOut with StudyOut with TourOut with SimulOut with ChallengeOut

object LilaOut {

  // site

  case class Move(game: Game.Id, lastUci: Uci, fen: FEN) extends SiteOut
  case class Mlat(millis: Double) extends SiteOut
  case class TellFlag(flag: String, json: JsonString) extends SiteOut
  case class TellUsers(users: Iterable[User.ID], json: JsonString) extends SiteOut
  case class TellAll(json: JsonString) extends SiteOut
  case class DisconnectUser(user: User.ID) extends SiteOut
  case class TellSri(sri: Sri, json: JsonString) extends SiteOut with LobbyOut with StudyOut
  case class SetTroll(user: User.ID, v: IsTroll) extends SiteOut
  case class Impersonate(user: User.ID, by: Option[User.ID]) extends SiteOut

  // lobby

  case class LobbyPairings(pairings: List[(Sri, Game.FullId)]) extends LobbyOut
  case class TellLobby(json: JsonString) extends LobbyOut
  case class TellLobbyActive(json: JsonString) extends LobbyOut
  case class TellLobbyUsers(users: Iterable[User.ID], json: JsonString) extends LobbyOut

  case class NbMembers(value: Int) extends LobbyOut

  case class NbRounds(value: Int) extends LobbyOut

  case class TellSris(sri: Seq[Sri], json: JsonString) extends LobbyOut

  // room

  case class TellRoom(roomId: RoomId, json: JsonString) extends AnyRoomOut
  case class TellRoomVersion(roomId: RoomId, version: SocketVersion, troll: IsTroll, json: JsonString) extends AnyRoomOut
  case class TellRoomUser(roomId: RoomId, user: User.ID, json: JsonString) extends AnyRoomOut with SiteOut
  case class TellRoomUsers(roomId: RoomId, users: Iterable[User.ID], json: JsonString) extends AnyRoomOut with SiteOut

  case class RoomStop(roomId: RoomId) extends AnyRoomOut

  // study

  case class RoomIsPresent(reqId: Int, roomId: RoomId, userId: User.ID) extends StudyOut

  // tour

  case class GetWaitingUsers(roomId: RoomId, name: String) extends TourOut

  // round

  case class RoundVersion(gameId: Game.Id, version: SocketVersion, flags: RoundEventFlags, tpe: String, data: JsonString) extends RoundOut
  case class RoundTourStanding(tourId: Tour.ID, data: JsonString) extends RoundOut
  case class RoundResyncPlayer(fullId: Game.FullId) extends RoundOut
  case class RoundGone(fullId: Game.FullId, v: Boolean) extends RoundOut
  case class RoundBotOnline(gameId: Game.Id, color: Color, v: Boolean) extends RoundOut
  case class UserTvNewGame(gameId: Game.Id, userId: User.ID) extends RoundOut

  case class TvSelect(gameId: Game.Id, speed: chess.Speed, json: JsonString) extends RoundOut

  case object LilaBoot extends AnyRoomOut

  // impl

  private def get(args: String, nb: Int)(f: PartialFunction[Array[String], Option[LilaOut]]): Option[LilaOut] =
    f.applyOrElse(args.split(" ", nb), (_: Array[String]) => None)

  def read(str: String): Option[LilaOut] = {
    val parts = str.split(" ", 2)
    val args = parts.lift(1) getOrElse ""
    parts(0) match {

      case "move" => get(args, 3) {
        case Array(game, lastUci, fen) => Uci(lastUci) map { Move(Game.Id(game), _, FEN(fen)) }
      }

      case "mlat" => parseDoubleOption(args) map Mlat.apply

      case "tell/flag" => get(args, 2) {
        case Array(flag, payload) => Some(TellFlag(flag, JsonString(payload)))
      }

      case "tell/users" => get(args, 2) {
        case Array(users, payload) => Some(TellUsers(commas(users), JsonString(payload)))
      }

      case "tell/sri" => get(args, 2) {
        case Array(sri, payload) => Some(TellSri(Sri(sri), JsonString(payload)))
      }

      case "tell/all" => Some(TellAll(JsonString(args)))

      case "disconnect/user" => Some(DisconnectUser(args))

      case "lobby/pairings" => Some(LobbyPairings {
        commas(args).map(_ split ':').collect {
          case Array(sri, fullId) => (Sri(sri), Game.FullId(fullId))
        }.toList
      })

      case "tell/lobby" => Some(TellLobby(JsonString(args)))
      case "tell/lobby/active" => Some(TellLobbyActive(JsonString(args)))

      case "tell/lobby/users" => get(args, 2) {
        case Array(users, payload) => Some(TellLobbyUsers(commas(users), JsonString(payload)))
      }

      case "member/nb" => parseIntOption(args) map NbMembers.apply

      case "round/nb" => parseIntOption(args) map NbRounds.apply

      case "mod/troll/set" => get(args, 2) {
        case Array(user, v) => Some(SetTroll(user, IsTroll(boolean(v))))
      }
      case "mod/impersonate" => get(args, 2) {
        case Array(user, by) => Some(Impersonate(user, optional(by)))
      }

      case "tell/sris" => get(args, 2) {
        case Array(sris, payload) => Some(TellSris(
          commas(sris).toSeq map Sri.apply,
          JsonString(payload)
        ))
      }

      case "tell/room" => get(args, 2) {
        case Array(roomId, payload) => Some(TellRoom(RoomId(roomId), JsonString(payload)))
      }

      case "tell/room/version" => get(args, 4) {
        case Array(roomId, version, troll, payload) => parseIntOption(version) map { sv =>
          TellRoomVersion(RoomId(roomId), SocketVersion(sv), IsTroll(boolean(troll)), JsonString(payload))
        }
      }

      case "tell/room/user" => get(args, 3) {
        case Array(roomId, userId, payload) => Some(TellRoomUser(RoomId(roomId), userId, JsonString(payload)))
      }
      case "tell/room/users" => get(args, 3) {
        case Array(roomId, userIds, payload) => Some(TellRoomUsers(RoomId(roomId), commas(userIds), JsonString(payload)))
      }

      case "room/stop" => Some(RoomStop(RoomId(args)))

      case "room/present" => get(args, 3) {
        case Array(reqIdS, roomId, userId) => parseIntOption(reqIdS) map { reqId =>
          RoomIsPresent(reqId, RoomId(roomId), userId)
        }
      }

      case "tour/get/waiting" => get(args, 2) {
        case Array(roomId, name) => Some(GetWaitingUsers(RoomId(roomId), name))
      }

      case "r/ver" => get(args, 5) {
        case Array(roomId, version, f, tpe, data) => parseIntOption(version) map { sv =>
          val flags = RoundEventFlags(
            watcher = f contains 's',
            owner = f contains 'p',
            player =
              if (f contains 'w') Some(chess.White)
              else if (f contains 'b') Some(chess.Black)
              else None,
            moveBy =
              if (f contains 'B') Some(chess.Black)
              else if (f contains 'W') Some(chess.White)
              else None,
            troll = f contains 't'
          )
          RoundVersion(Game.Id(roomId), SocketVersion(sv), flags, tpe, JsonString(data))
        }
      }

      case "r/tour/standing" => get(args, 2) {
        case Array(tourId, data) => Some(RoundTourStanding(tourId, JsonString(data)))
      }

      case "r/resync/player" => Some(RoundResyncPlayer(Game.FullId(args)))

      case "r/gone" => get(args, 2) {
        case Array(fullId, gone) => Some(RoundGone(Game.FullId(fullId), boolean(gone)))
      }

      case "r/tv/user" => get(args, 2) {
        case Array(gameId, userId) => Some(UserTvNewGame(Game.Id(gameId), userId))
      }

      case "r/bot/online" => get(args, 3) {
        case Array(gameId, color, v) => Some(RoundBotOnline(Game.Id(gameId), readColor(color), boolean(v)))
      }

      // tv

      case "tv/select" => get(args, 3) {
        case Array(gameId, speedS, data) =>
          parseIntOption(speedS) flatMap chess.Speed.apply map { speed =>
            TvSelect(Game.Id(gameId), speed, JsonString(data))
          }
      }

      case "boot" => Some(LilaBoot)

      case _ => None
    }
  }

  def commas(str: String): Array[String] = if (str == "-") Array.empty else str split ','
  def boolean(str: String): Boolean = str == "+"
  def readColor(str: String): Color = Color(str == "w")
  def optional(str: String): Option[String] = if (str == "-") None else Some(str)
}
