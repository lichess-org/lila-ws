package lila.ws

import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import scala.concurrent.duration.*

import SocialGraph.UserMeta
import ipc.ClientIn.following.*

import scala.concurrent.{ ExecutionContext, Future }

final class FriendList(
    users: Users,
    graph: SocialGraph,
    mongo: Mongo
)(using ec: ExecutionContext):

  import FriendList.*

  private val userDatas: AsyncLoadingCache[User.Id, Option[UserData]] = Scaffeine()
    .expireAfterAccess(20.minutes)
    .buildAsyncFuture(mongo.userData)

  def start(userId: User.Id, emit: Emit[ipc.ClientIn]): Future[Unit] =
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

  def follow(left: User.Id, right: User.Id): Unit =
    graph.follow(left, right)

  def unFollow(left: User.Id, right: User.Id) = graph.unfollow(left, right)

  def startPlaying(userId: User.Id) =
    update(userId, Playing.apply)(_.withPlaying(true))

  def stopPlaying(userId: User.Id) =
    update(userId, StoppedPlaying.apply)(_.withPlaying(false))

  // a user WS closes
  def onClientStop(userId: User.Id) = graph.unsubscribe(userId)

  // user logs in
  private def onConnect(userId: User.Id): Unit =
    updateView(userId, Enters.apply)(_.withOnline(true))

  // user logs off
  private def onDisconnect(userId: User.Id) =
    update(userId, Leaves.apply)(_.withOnline(false))

  private def update(userId: User.Id, msg: User.Id => ipc.ClientIn)(update: UserMeta => UserMeta) =
    graph.tell(userId, update) foreach { case (subject, subs) =>
      if (subs.nonEmpty) users.tellMany(subs, msg(subject.id))
    }

  private def updateView(userId: User.Id, msg: UserView => ipc.ClientIn)(update: UserMeta => UserMeta) =
    graph.tell(userId, update) foreach { case (subject, subs) =>
      if (subs.nonEmpty) userDatas.get(subject.id) foreach {
        _ foreach { data =>
          users.tellMany(subs, msg(UserView(subject.id, data, subject.meta)))
        }
      }
    }

  Bus.internal.subscribe(
    "users",
    {
      case ipc.LilaIn.ConnectUser(user, _)   => onConnect(user)
      case ipc.LilaIn.DisconnectUsers(users) => users foreach onDisconnect
    }
  )

object FriendList:

  case class UserData(name: User.Name, title: Option[User.Title], patron: User.Patron):
    def titleName = User.TitleName(name, title)

  case class UserView(id: User.Id, data: UserData, meta: SocialGraph.UserMeta)
