package lila.ws

import org.apache.pekko.actor.typed.Scheduler
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

import ipc.*

final class Users(using scheduler: Scheduler, ec: Executor):

  private val users       = new ConcurrentHashMap[User.Id, Set[Client]](32768)
  private val disconnects = ConcurrentHashMap.newKeySet[User.Id](2048)

  private def publish(msg: Matchable) = Bus.internal.publish("users", msg)

  scheduler.scheduleWithFixedDelay(7.seconds, 5.seconds): () =>
    publish(LilaIn.DisconnectUsers(disconnects.iterator.asScala.toSet))
    disconnects.clear()

  def connect(user: User.Id, client: Client, silently: Boolean = false): Unit =
    users.compute(
      user,
      {
        case (_, null) =>
          if !disconnects.remove(user) then publish(LilaIn.ConnectUser(user, silently))
          Set(client)
        case (_, clients) =>
          clients + client
      }
    )

  def disconnect(user: User.Id, client: Client): Unit =
    users.computeIfPresent(
      user,
      (_, clients) =>
        val newClients = clients - client
        if newClients.isEmpty then
          disconnects add user
          null
        else newClients
    )

  def tellOne(userId: User.Id, payload: ClientMsg): Unit =
    Option(users get userId) foreach:
      _ foreach { _ ! payload }

  def tellMany(userIds: Iterable[User.Id], payload: ClientMsg): Unit =
    userIds foreach { tellOne(_, payload) }

  def kick(userId: User.Id): Unit =
    Option(users get userId) foreach:
      _ foreach { _ ! ClientCtrl.Disconnect }

  def setTroll(userId: User.Id, v: IsTroll): Unit =
    Option(users get userId) foreach:
      _ foreach { _ ! ipc.SetTroll(v) }

  def isOnline(userId: User.Id): Boolean = users containsKey userId

  def size = users.size
