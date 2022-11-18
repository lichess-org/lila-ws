package lila.ws

import akka.actor.typed.Scheduler
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.*

import ipc.*

final class Users(using scheduler: Scheduler, ec: ExecutionContext):

  private val users       = new ConcurrentHashMap[UserId, Set[Client]](32768)
  private val disconnects = ConcurrentHashMap.newKeySet[UserId](2048)

  private def publish(msg: Matchable) = Bus.internal.publish("users", msg)

  scheduler.scheduleWithFixedDelay(7.seconds, 5.seconds) { () =>
    publish(LilaIn.DisconnectUsers(disconnects.iterator.asScala.toSet))
    disconnects.clear()
  }

  def connect(user: UserId, client: Client, silently: Boolean = false): Unit =
    users.compute(
      user,
      {
        case (_, null) =>
          if (!disconnects.remove(user)) publish(LilaIn.ConnectUser(user, silently))
          Set(client)
        case (_, clients) =>
          clients + client
      }
    )

  def disconnect(user: UserId, client: Client): Unit =
    users.computeIfPresent(
      user,
      (_, clients) => {
        val newClients = clients - client
        if (newClients.isEmpty) {
          disconnects add user
          null
        } else newClients
      }
    )

  def tellOne(userId: UserId, payload: ClientMsg): Unit =
    Option(users get userId) foreach {
      _ foreach { _ ! payload }
    }

  def tellMany(userIds: Iterable[UserId], payload: ClientMsg): Unit =
    userIds foreach { tellOne(_, payload) }

  def kick(userId: UserId): Unit =
    Option(users get userId) foreach {
      _ foreach { _ ! ClientCtrl.Disconnect }
    }

  def setTroll(userId: UserId, v: IsTroll): Unit =
    Option(users get userId) foreach {
      _ foreach { _ ! ipc.SetTroll(v) }
    }

  def isOnline(userId: UserId): Boolean = users containsKey userId

  def size = users.size
