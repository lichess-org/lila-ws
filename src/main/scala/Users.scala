package lila.ws

import akka.actor.typed.Scheduler
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

import ipc._

final class Users(implicit scheduler: Scheduler, ec: ExecutionContext) {

  private val users       = new ConcurrentHashMap[User.ID, Set[Client]](32768)
  private val disconnects = ConcurrentHashMap.newKeySet[User.ID](2048)

  private def publish(msg: Any) = Bus.internal.publish("users", msg)

  scheduler.scheduleWithFixedDelay(7.seconds, 5.seconds) { () =>
    publish(LilaIn.DisconnectUsers(disconnects.iterator.asScala.toSet))
    disconnects.clear()
  }

  def connect(user: User, client: Client, silently: Boolean = false): Unit =
    users.compute(
      user.id,
      {
        case (_, null) =>
          if (!disconnects.remove(user.id)) publish(LilaIn.ConnectUser(user, silently))
          Set(client)
        case (_, clients) =>
          clients + client
      }
    )

  def disconnect(user: User, client: Client): Unit =
    users.computeIfPresent(
      user.id,
      (_, clients) => {
        val newClients = clients - client
        if (newClients.isEmpty) {
          disconnects add user.id
          null
        } else newClients
      }
    )

  def tellOne(userId: User.ID, payload: ClientMsg): Unit =
    Option(users get userId) foreach {
      _ foreach { _ ! payload }
    }

  def tellMany(userIds: Iterable[User.ID], payload: ClientMsg): Unit =
    userIds foreach { tellOne(_, payload) }

  def kick(userId: User.ID): Unit =
    Option(users get userId) foreach {
      _ foreach { _ ! ClientCtrl.Disconnect }
    }

  def setTroll(userId: User.ID, v: IsTroll): Unit =
    Option(users get userId) foreach {
      _ foreach { _ ! ipc.SetTroll(v) }
    }

  def isOnline(userId: User.ID): Boolean = users containsKey userId

  def size = users.size
}
