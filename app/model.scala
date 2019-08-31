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

case class Flag(value: String) extends AnyVal
