package lila.ws
package ipc

import chess.format.{ FEN, Uci }
import java.lang.Double.parseDouble
import java.lang.Integer.parseInt
import play.api.libs.json._
import scala.util.Try

sealed trait LilaOut extends LilaMsg

sealed trait LobbyOut extends LilaOut

sealed trait SiteOut extends LilaOut

object LilaOut {

  // site

  case class Move(game: Game.ID, lastUci: Uci, fen: FEN) extends SiteOut

  case class Mlat(millis: Double) extends SiteOut

  case class TellFlag(flag: String, json: JsonString) extends SiteOut

  case class TellUsers(users: Iterable[User.ID], json: JsonString) extends SiteOut

  case class TellAll(json: JsonString) extends SiteOut

  case class DisconnectUser(user: User.ID) extends SiteOut

  // site, lobby

  case class TellSri(sri: Sri, json: JsonString) extends SiteOut with LobbyOut

  // lobby

  case class TellLobby(json: JsonString) extends LobbyOut
  case class TellLobbyActive(json: JsonString) extends LobbyOut

  case class NbMembers(value: Int) extends LobbyOut

  case class NbRounds(value: Int) extends LobbyOut

  case class DisconnectSri(sri: Sri) extends LobbyOut

  case class TellSris(sri: Seq[Sri], json: JsonString) extends LobbyOut

  // impl

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
        case Array(flag, payload) => Some(TellFlag(flag, JsonString(payload)))
        case _ => None
      }

      case "tell/users" => args.split(" ", 2) match {
        case Array(users, payload) => Some(TellUsers(users split ",", JsonString(payload)))
        case _ => None
      }

      case "tell/sri" => args.split(" ", 2) match {
        case Array(sri, payload) => Some(TellSri(Sri(sri), JsonString(payload)))
        case _ => None
      }

      case "tell/all" => Some(TellAll(JsonString(args)))

      case "disconnect/user" => Some(DisconnectUser(args))

      case "disconnect/sri" => Some(DisconnectSri(Sri(args)))

      case "tell/lobby" => Some(TellLobby(JsonString(args)))
      case "tell/lobby/active" => Some(TellLobbyActive(JsonString(args)))

      case "member/nb" => Try(NbMembers(parseInt(args))).toOption

      case "round/nb" => Try(NbRounds(parseInt(args))).toOption

      case "tell/sris" => args.split(" ", 2) match {
        case Array(sris, payload) => Some(TellSris(
          sris.split(",").toSeq map Sri.apply,
          JsonString(payload)
        ))
        case _ => None
      }

      case _ => None
    }
  }
}
