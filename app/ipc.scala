package lichess.ws
package ipc

import play.api.libs.json.Json

case class Command(path: String, arg: String) {
  def args(nb: Int): Array[String] = arg.split(" ", nb)
  override def toString = s"$path $arg"
}
object Command {
  def apply(msg: String): Command = {
    val parts = msg.split(" ", 2)
    Command(parts(0), parts.lift(1).getOrElse(""))
  }
}

sealed trait LilaOut

object LilaOut {

  case class Move(game: Game.ID, lastUci: String, fen: String) extends LilaOut

  def parse(cmd: Command): Option[LilaOut] = cmd.path match {
    case "move" => cmd args 3 match {
      case Array(game, lastUci, fen) => Some(Move(game, lastUci, fen))
      case _ => None
    }
  }
}
