package lila.ws

import akka.actor.Scheduler
import akka.actor.typed.ActorRef
import java.util.concurrent.ConcurrentHashMap
import javax.inject._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

import ipc._

@Singleton
final class Users @Inject() (lilaIn: Emit[LilaIn.Site], scheduler: Scheduler)(implicit ec: ExecutionContext) {

  type Client = ActorRef[ClientMsg]

  private val users = new ConcurrentHashMap[User.ID, Set[Client]](32768)
  private val disconnects = ConcurrentHashMap.newKeySet[User.ID](2048)

  scheduler.scheduleWithFixedDelay(7.seconds, 5.seconds) { () =>
    lilaIn(LilaIn.DisconnectUsers(disconnects.iterator.asScala.toSet))
    disconnects.clear()
  }

  def connect(user: User, client: Client, silently: Boolean = false): Unit =
    users.compute(user.id, {
      case (_, null) =>
        if (disconnects.remove(user.id) == null && !silently) lilaIn(LilaIn.ConnectUser(user))
        Set(client)
      case (_, clients) =>
        clients + client
    })

  def disconnect(user: User, client: Client): Unit =
    users.computeIfPresent(user.id, (id, clients) => {
      val newClients = clients - client
      if (newClients.isEmpty) {
        disconnects add user.id
        null
      }
      else newClients
    })

  def tellOne(userId: User.ID, payload: ClientIn): Unit =
    Option(users get userId) foreach {
      _ foreach { _ ! payload }
    }

  def tellMany(userIds: Iterable[User.ID], payload: ClientIn): Unit =
    userIds foreach { tellOne(_, payload) }

  def kick(userId: User.ID): Unit =
    Option(users get userId) foreach {
      _ foreach { _ ! ClientCtrl.Disconnect }
    }

  def setTroll(userId: User.ID, v: IsTroll): Unit =
    Option(users get userId) foreach {
      _ foreach { _ ! ipc.SetTroll(v) }
    }
}
