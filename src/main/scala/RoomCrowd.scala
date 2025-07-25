package lila.ws

import cats.syntax.option.*

import ipc.*

final class RoomCrowd(json: CrowdJson, groupedWithin: util.GroupedWithin)(using ec: Executor):

  import RoomCrowd.*

  private val rooms = scalalib.ConcurrentMap[RoomId, RoomState](1024)
  export rooms.size

  def connect(roomId: RoomId, user: Option[User.Id]): Unit =
    rooms
      .compute(roomId)(_.getOrElse(RoomState()).connect(user).some)
      .foreach:
        publish(roomId, _)

  def disconnect(roomId: RoomId, user: Option[User.Id]): Unit =
    rooms
      .computeIfPresent(roomId): room =>
        val newRoom = room.disconnect(user)
        Option.unless(newRoom.isEmpty)(newRoom)
      .foreach(publish(roomId, _))

  def getUsers(roomId: RoomId): Set[User.Id] =
    rooms.get(roomId).fold(Set.empty[User.Id])(_.users.keySet)

  def isPresent(roomId: RoomId, userId: User.Id): Boolean =
    rooms.get(roomId).exists(_.users contains userId)

  def filterPresent(roomId: RoomId, userIds: Set[User.Id]): Set[User.Id] =
    rooms.get(roomId).fold(Set.empty[User.Id])(_.users.keySet.intersect(userIds))

  def getNbMembers(roomIds: Set[RoomId]): Map[RoomId, Int] =
    roomIds.view
      .map: id =>
        rooms.get(id).fold(id -> 0)(id -> _.nbMembers)
      .toMap

  private def publish(roomId: RoomId, room: RoomState): Unit =
    outputBatch(outputOf(roomId, room))

  private val outputBatch = groupedWithin[Output](1024, 1.second): outputs =>
    val lastPerRoomId: Iterable[Output] = outputs
      .foldLeft(Map.empty[RoomId, Output]): (crowds, crowd) =>
        crowds.updated(crowd.roomId, crowd)
      .values
    lastPerRoomId.foreach: output =>
      json
        .room(output)
        .foreach:
          Bus.publish(_.room(output.roomId), _)

object RoomCrowd:

  case class Output(
      roomId: RoomId,
      members: Int,
      users: Iterable[User.Id],
      anons: Int
  )

  def outputOf(roomId: RoomId, room: RoomState) =
    Output(
      roomId = roomId,
      members = room.nbMembers,
      users = room.users.keys,
      anons = room.anons
    )

  case class RoomState(
      anons: Int = 0,
      users: Map[User.Id, Int] = Map.empty
  ):
    def nbUsers = users.size
    def nbMembers = anons + nbUsers
    def isEmpty = nbMembers < 1

    def connect(user: Option[User.Id]) =
      user.fold(copy(anons = anons + 1)): u =>
        copy(users = users.updatedWith(u)(cur => Some(cur.fold(1)(_ + 1))))
    def disconnect(user: Option[User.Id]) =
      user.fold(copy(anons = anons - 1)): u =>
        copy(users = users.updatedWith(u)(_.map(_ - 1).filter(_ > 0)))
