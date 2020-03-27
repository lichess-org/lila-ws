package lila.ws

import scala.concurrent.{ ExecutionContext, Future }

final class FriendList(
    users: Users,
    graph: SocialGraph,
    mongo: Mongo
)(implicit ec: ExecutionContext) {

  def start(userId: User.ID, emit: Emit[ipc.ClientIn]): Future[Unit] =
    graph.followed(userId) map { all =>
      val online = all.filter(u => users.isOnline(u.id))
      emit(ipc.ClientIn.FriendList(online))
    }

  def follow(left: User.ID, right: User.ID): Future[Unit] =
    mongo.userRecord(right) map {
      _ foreach { graph.follow(left, _) }
    }

  def unFollow(left: User.ID, right: User.ID) = graph.unfollow(left, right)

  def startPlaying(userId: User.ID) = graph.tell(userId, _.copy(playing = true))

  def stopPlaying(userId: User.ID) = graph.tell(userId, _.copy(playing = false))

  private def onConnect(userId: User.ID) = graph.tell(userId, _.copy(online = true))

  private def onDisconnect(userId: User.ID) = graph.tell(userId, _.copy(online = false))

  Bus.internal.subscribe("users", {
    case ipc.LilaIn.ConnectUser(user, _)   => onConnect(user.id)
    case ipc.LilaIn.DisconnectUsers(users) => users foreach onDisconnect
  })
}
