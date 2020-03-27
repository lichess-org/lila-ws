package lila.ws

import SocialGraph.{ UserInfo, UserMeta }
import ipc.ClientIn.following._

import scala.concurrent.{ ExecutionContext, Future }

final class FriendList(
    users: Users,
    graph: SocialGraph,
    mongo: Mongo
)(implicit ec: ExecutionContext) {

  def start(userId: User.ID, emit: Emit[ipc.ClientIn]): Future[Unit] =
    graph.followed(userId) map { all =>
      emit(FriendList(all.filter(u => u.meta.exists(_.online))))
    }

  def follow(left: User.ID, right: User.ID): Future[Unit] =
    mongo.userRecord(right) map {
      _ foreach { graph.follow(left, _) }
    }

  def unFollow(left: User.ID, right: User.ID) = graph.unfollow(left, right)

  def startPlaying(userId: User.ID) =
    update(userId, Playing.apply)(_.copy(playing = true))

  def stopPlaying(userId: User.ID) =
    update(userId, StoppedPlaying.apply)(_.copy(playing = false))

  def joinStudy(userId: User.ID) =
    update(userId, JoinedStudy.apply)(_.copy(studying = true))

  def leaveStudy(userId: User.ID) =
    update(userId, LeftStudy.apply)(_.copy(studying = false))

  private def onConnect(userId: User.ID): Unit =
    update(userId, Enters.apply)(_.copy(online = true))

  private def onDisconnect(userId: User.ID) =
    update(userId, Leaves.apply)(_.copy(online = false))

  private def update(userId: User.ID, msg: UserInfo => ipc.ClientIn)(update: UserMeta => UserMeta) =
    graph.tell(userId, update) foreach {
      case (subject, subs) =>
        if (subs.nonEmpty) users.tellMany(subs, msg(subject))
    }

  Bus.internal.subscribe("users", {
    case ipc.LilaIn.ConnectUser(user, _)   => onConnect(user.id)
    case ipc.LilaIn.DisconnectUsers(users) => users foreach onDisconnect
  })
}
