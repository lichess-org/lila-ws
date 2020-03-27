package lila.ws

import scala.concurrent.{ ExecutionContext, Future }

final class FriendList(
    users: Users,
    graph: SocialGraph,
    mongo: Mongo
)(implicit ec: ExecutionContext) {

  def start(userId: User.ID, emit: Emit[ipc.ClientIn]): Future[Unit] =
    graph.followed(userId) map { all =>
      emit(ipc.ClientIn.FriendList(all.filter(u => u.meta.exists(_.online))))
    }

  def follow(left: User.ID, right: User.ID): Future[Unit] =
    mongo.userRecord(right) map {
      _ foreach { graph.follow(left, _) }
    }

  def unFollow(left: User.ID, right: User.ID) = graph.unfollow(left, right)

  def startPlaying(userId: User.ID) = graph.tell(userId, _.copy(playing = true))

  def stopPlaying(userId: User.ID) = graph.tell(userId, _.copy(playing = false))

  private def onConnect(userId: User.ID): Unit =
    graph.tell(userId, _.copy(online = true)) foreach {
      case (subject, subs) =>
        users.tellMany(subs, ipc.ClientIn.FollowingEnters(subject))
    }

  private def onDisconnect(userId: User.ID) = graph.tell(userId, _.copy(online = false))

  Bus.internal.subscribe("users", {
    case ipc.LilaIn.ConnectUser(user, _)   => onConnect(user.id)
    case ipc.LilaIn.DisconnectUsers(users) => users foreach onDisconnect
  })
}
