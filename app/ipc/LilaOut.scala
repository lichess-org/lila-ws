package lila.ws
package ipc

import chess.format.{ FEN, Uci }
import play.api.libs.json._
import scala.util.Try

import lila.ws.util.Util._

sealed trait LilaOut extends LilaMsg

sealed trait LobbyOut extends LilaOut

sealed trait RoomOut extends LilaOut

sealed trait SimulOut extends RoomOut

sealed trait TourOut extends RoomOut

sealed trait SiteOut extends LilaOut

object LilaOut {

  // site

  case class Move(game: Game.ID, lastUci: Uci, fen: FEN) extends SiteOut

  case class Mlat(millis: Double) extends SiteOut

  case class TellFlag(flag: String, json: JsonString) extends SiteOut

  case class TellUsers(users: Iterable[User.ID], json: JsonString) extends SiteOut

  case class TellAll(json: JsonString) extends SiteOut

  case class DisconnectUser(user: User.ID) extends SiteOut

  // site, lobby, simul

  case class TellSri(sri: Sri, json: JsonString) extends SiteOut with LobbyOut

  // lobby

  case class LobbyPairings(pairings: List[(Sri, Game.FullID)]) extends LobbyOut
  case class TellLobby(json: JsonString) extends LobbyOut
  case class TellLobbyActive(json: JsonString) extends LobbyOut
  case class TellLobbyUsers(users: Iterable[User.ID], json: JsonString) extends LobbyOut

  case class NbMembers(value: Int) extends LobbyOut

  case class NbRounds(value: Int) extends LobbyOut

  case class TellSris(sri: Seq[Sri], json: JsonString) extends LobbyOut

  // simul, tour

  case class TellRoom(roomId: RoomId, json: JsonString) extends SimulOut with TourOut
  case class TellRoomVersion(roomId: RoomId, version: SocketVersion, troll: IsTroll, json: JsonString) extends SimulOut with TourOut
  case class TellRoomUser(roomId: RoomId, user: User.ID, json: JsonString) extends SiteOut with SimulOut with TourOut
  case class GetRoomUsers(roomId: RoomId) extends TourOut

  case class RoomStart(roomId: RoomId) extends SimulOut with TourOut
  case class RoomStop(roomId: RoomId) extends SimulOut with TourOut

  // impl

  def read(str: String): Option[LilaOut] = {
    val parts = str.split(" ", 2)
    val args = parts.lift(1) getOrElse ""
    parts(0) match {

      case "move" => args.split(" ", 3) match {
        case Array(game, lastUci, fen) => Uci(lastUci) map { Move(game, _, FEN(fen)) }
        case _ => None
      }

      case "mlat" => parseDoubleOption(args) map Mlat.apply

      case "tell/flag" => args.split(" ", 2) match {
        case Array(flag, payload) => Some(TellFlag(flag, JsonString(payload)))
        case _ => None
      }

      case "tell/users" => args.split(" ", 2) match {
        case Array(users, payload) => Some(TellUsers(commas(users), JsonString(payload)))
        case _ => None
      }

      case "tell/sri" => args.split(" ", 2) match {
        case Array(sri, payload) => Some(TellSri(Sri(sri), JsonString(payload)))
        case _ => None
      }

      case "tell/all" => Some(TellAll(JsonString(args)))

      case "disconnect/user" => Some(DisconnectUser(args))

      case "lobby/pairings" => Some(LobbyPairings {
        commas(args).map(_ split ':').collect {
          case Array(sri, fullId) => (Sri(sri), fullId)
        }.toList
      })

      case "tell/lobby" => Some(TellLobby(JsonString(args)))
      case "tell/lobby/active" => Some(TellLobbyActive(JsonString(args)))

      case "tell/lobby/users" => args.split(" ", 2) match {
        case Array(users, payload) => Some(TellLobbyUsers(commas(users), JsonString(payload)))
        case _ => None
      }

      case "member/nb" => parseIntOption(args) map NbMembers.apply

      case "round/nb" => parseIntOption(args) map NbRounds.apply

      case "tell/sris" => args.split(" ", 2) match {
        case Array(sris, payload) => Some(TellSris(
          commas(sris).toSeq map Sri.apply,
          JsonString(payload)
        ))
        case _ => None
      }

      case "tell/room" => args.split(" ", 2) match {
        case Array(roomId, payload) => Some(TellRoom(RoomId(roomId), JsonString(payload)))
        case _ => None
      }

      case "tell/room/version" => args.split(" ", 4) match {
        case Array(roomId, version, troll, payload) => parseIntOption(version) map { sv =>
          TellRoomVersion(RoomId(roomId), SocketVersion(sv), IsTroll(troll == "true"), JsonString(payload))
        }
        case _ => None
      }

      case "tell/room/user" => args.split(" ", 3) match {
        case Array(roomId, userId, payload) => Some(TellRoomUser(RoomId(roomId), userId, JsonString(payload)))
        case _ => None
      }

      case "room/get/users" => Some(GetRoomUsers(RoomId(args)))

      case "room/start" => Some(RoomStart(RoomId(args)))
      case "room/stop" => Some(RoomStop(RoomId(args)))

      case _ => None
    }
  }

  def commas(str: String): Array[String] = if (str == "-") Array.empty else str split ','
}
