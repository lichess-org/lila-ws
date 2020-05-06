package lila.ws

import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import scala.concurrent.duration._

import SocialGraph.UserMeta
import ipc.ClientIn.following._

import scala.concurrent.{ ExecutionContext, Future }

final class FriendList(
    users: Users,
    graph: SocialGraph,
    mongo: Mongo
)(implicit ec: ExecutionContext) {

  import FriendList._

  private val userDatas: AsyncLoadingCache[User.ID, Option[UserData]] = Scaffeine()
    .expireAfterAccess(20.minutes)
    .buildAsyncFuture(mongo.userData)

  def start(userId: User.ID, emit: Emit[ipc.ClientIn]): Future[Unit] =
    graph.followed(userId) flatMap { entries =>
      Future.sequence {
        entries.collect {
          case u if u.meta.online =>
            userDatas
              .get(u.id)
              .map {
                _ map { UserView(u.id, _, u.meta) }
              }(ExecutionContext.parasitic)
        }
      } map { views =>
        emit(Onlines(views.flatten))
      }
    }

  def follow(left: User.ID, right: User.ID): Unit =
    graph.follow(left, right)

  def unFollow(left: User.ID, right: User.ID) = graph.unfollow(left, right)

  def startPlaying(userId: User.ID) =
    update(userId, Playing.apply)(_.withPlaying(true))

  def stopPlaying(userId: User.ID) =
    update(userId, StoppedPlaying.apply)(_.withPlaying(false))

  // a user WS closes
  def onClientStop(userId: User.ID) =
    graph.unsubscribe(userId)

  // user logs in
  private def onConnect(userId: User.ID): Unit =
    updateView(userId, Enters.apply)(_.withOnline(true))

  // user logs off
  private def onDisconnect(userId: User.ID) =
    update(userId, Leaves.apply)(_.withOnline(false))

  private def update(userId: User.ID, msg: User.ID => ipc.ClientIn)(update: UserMeta => UserMeta) =
    graph.tell(userId, update) foreach {
      case (subject, subs) =>
        if (subs.nonEmpty) users.tellMany(subs, msg(subject.id))
    }

  private def updateView(userId: User.ID, msg: UserView => ipc.ClientIn)(update: UserMeta => UserMeta) =
    graph.tell(userId, update) foreach {
      case (subject, subs) =>
        if (subs.nonEmpty) userDatas.get(subject.id) foreach {
          _ foreach { data =>
            users.tellMany(subs, msg(UserView(subject.id, data, subject.meta)))
          }
        }
    }

  Bus.internal.subscribe(
    "users",
    {
      case ipc.LilaIn.ConnectUser(user, _)   => onConnect(user.id)
      case ipc.LilaIn.DisconnectUsers(users) => users foreach onDisconnect
    }
  )
}

object FriendList {

  case class UserData(name: String, title: Option[String], patron: Boolean) {
    def titleName = title.fold(name)(_ + " " + name)
  }

  case class UserView(id: User.ID, data: UserData, meta: SocialGraph.UserMeta)
}
