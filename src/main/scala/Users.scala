package lila.ws

import cats.syntax.option.*

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

import ipc.*

final class Users(using scheduler: Scheduler, ec: Executor):

  private val users = scalalib.ConcurrentMap[User.Id, Set[Client]](32768)
  export users.{ size, containsKey as isOnline }

  private val disconnects = ConcurrentHashMap.newKeySet[User.Id](2048)

  private def publish(msg: Matchable) = Bus.internal.publish("users", msg)

  scheduler.scheduleWithFixedDelay(7.seconds, 5.seconds): () =>
    publish(LilaIn.DisconnectUsers(disconnects.iterator.asScala.toSet))
    disconnects.clear()

  def connect(user: User.Id, client: Client, silently: Boolean = false): Unit =
    users.compute(user):
      case None =>
        if !disconnects.remove(user) then publish(LilaIn.ConnectUser(user, silently))
        Set(client).some
      case Some(clients) => (clients + client).some

  def disconnect(user: User.Id, client: Client): Unit =
    users.computeIfPresent(user): clients =>
      val newClients = clients - client
      if newClients.isEmpty then
        disconnects.add(user)
        none
      else newClients.some

  def tellOne(userId: User.Id, payload: ClientMsg): Unit =
    users
      .get(userId)
      .foreach:
        _.foreach { _ ! payload }

  def tellMany(userIds: Iterable[User.Id], payload: ClientMsg): Unit =
    userIds.foreach { tellOne(_, payload) }

  def kick(userId: User.Id): Unit =
    users
      .get(userId)
      .foreach:
        _.foreach { _ ! ClientCtrl.Disconnect("user kick") }

  def setTroll(userId: User.Id, v: IsTroll): Unit =
    users
      .get(userId)
      .foreach:
        _.foreach { _ ! ipc.SetTroll(v) }
