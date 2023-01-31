package lila.ws

import com.github.blemale.scaffeine.{ Cache, Scaffeine }

object Palantir:

  private class Channel:

    private val members: Cache[User.Id, Unit] = Scaffeine()
      .expireAfterWrite(7.seconds)
      .build[User.Id, Unit]()

    def add(userId: User.Id) = members.put(userId, ())

    def userIds = members.asMap().keys

  private val channels: Cache[RoomId, Channel] = Scaffeine()
    .expireAfterWrite(1.minute)
    .build[RoomId, Channel]()

  def respondToPing(roomId: RoomId, user: User.Id): ipc.ClientIn.Palantir =
    val channel = channels.get(roomId, _ => new Channel)
    channel.add(user)
    Monitor.palantirChannels.update(channels.estimatedSize().toDouble)
    ipc.ClientIn.Palantir(channel.userIds.filter(user.!=))
