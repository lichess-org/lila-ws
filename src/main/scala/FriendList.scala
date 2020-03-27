package lila.ws

import SocialGraph.{ UserInfo, UserMeta }

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

  def startPlaying(userId: User.ID) =
    update(userId, ipc.ClientIn.FollowingPlaying.apply)(_.copy(playing = true))

  def stopPlaying(userId: User.ID) =
    update(userId, ipc.ClientIn.FollowingStoppedPlaying.apply)(_.copy(playing = false))

  private def onConnect(userId: User.ID): Unit =
    update(userId, ipc.ClientIn.FollowingEnters.apply)(_.copy(online = true))

  private def onDisconnect(userId: User.ID) =
    update(userId, ipc.ClientIn.FollowingLeaves.apply)(_.copy(online = false))

  private def update(userId: User.ID, msg: UserInfo => ipc.ClientIn)(update: UserMeta => UserMeta) =
    graph.tell(userId, update) foreach {
      case (subject, subs) =>
        val online = subs.filter(users.isOnline)
        if (online.nonEmpty) users.tellMany(online, msg(subject))
    }

  Bus.internal.subscribe("users", {
    case ipc.LilaIn.ConnectUser(user, _)   => onConnect(user.id)
    case ipc.LilaIn.DisconnectUsers(users) => users foreach onDisconnect
  })
}
