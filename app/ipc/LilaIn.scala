package lila.ws
package ipc

sealed trait LilaIn {
  def write: String
}

object LilaIn {

  case class Watch(id: Game.ID) extends LilaIn {
    def write = s"watch $id"
  }

  case class Lags(value: Map[User.ID, Int]) extends LilaIn {
    def write = s"lags ${value.map { case (user, lag) => s"$user:$lag" } mkString ","}"
  }

  case class Connections(count: Int) extends LilaIn {
    def write = s"connections $count"
  }
}
