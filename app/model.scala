package lila.ws

case class User(id: User.ID) extends AnyVal

object User {
  type ID = String
}

case class Game(id: Game.ID) extends AnyVal

object Game {
  type ID = String
}

case class Sri(value: String) extends AnyVal

object Sri {
  type Str = String
}

case class Flag private (value: String) extends AnyVal

object Flag {
  def make(value: String) = value match {
    case "simul" | "tournament" => Some(Flag(value))
    case _ => None
  }
}

case class Path(value: String) extends AnyVal

case class ChapterId(value: String) extends AnyVal

case class JsonString(value: String) extends AnyVal
