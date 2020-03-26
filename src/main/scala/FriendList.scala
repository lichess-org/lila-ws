package lila.ws

import scala.concurrent.ExecutionContext

final class FriendList(
    users: Users,
    graph: SocialGraph,
    mongo: Mongo
)(implicit ec: ExecutionContext) {

  def start(userId: User.ID, emit: Emit[ipc.ClientIn]): Unit =
    graph.followed(userId) foreach { all =>
      val online = all.filter(u => users.isOnline(u.id))
      println(all)
      println(online)
      emit(ipc.ClientIn.FriendList(online))
    }

  def follow(left: User.ID, right: User.ID) = mongo.userRecord(right) map {
    _ foreach { graph.follow(left, _) }
  }

  def unFollow(left: User.ID, right: User.ID) = graph.unfollow(left, right)

  private def onConnect(userId: User.ID) = graph.tell(userId, SocialGraph.UserMeta(online = true))

  private def onDisconnect(userId: User.ID) = graph.tell(userId, SocialGraph.UserMeta(online = false))

  Bus.internal.subscribe("users", {
    case ipc.LilaIn.ConnectUser(user, _)   => onConnect(user.id)
    case ipc.LilaIn.DisconnectUsers(users) => users foreach onDisconnect
  })
}
