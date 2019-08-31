package lila.ws
package ipc

sealed trait LilaIn {
  def write: String
}

object LilaIn {

  case class Watch(id: Game.ID) extends LilaIn {
    def write = s"watch $id"
  }

  case class Unwatch(id: Game.ID) extends LilaIn {
    def write = s"unwatch $id"
  }

  case class Notified(userId: User.ID) extends LilaIn {
    def write = s"notified $userId"
  }

  case class Friends(userId: User.ID) extends LilaIn {
    def write = s"friends $userId"
  }

  case class Lags(value: Map[User.ID, Int]) extends LilaIn {
    def write = s"lags ${value.map { case (user, lag) => s"$user:$lag" } mkString ","}"
  }

  case class Connections(count: Int) extends LilaIn {
    def write = s"connections $count"
  }

  case class Connect(user: User) extends LilaIn {
    def write = s"connect ${user.id}"
  }

  case class Disconnect(user: User) extends LilaIn {
    def write = s"disconnect ${user.id}"
  }

  case object DisconnectAll extends LilaIn {
    def write = "disconnect/all"
  }
}
