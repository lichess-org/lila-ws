package lila.ws

import scala.concurrent.duration.*
import com.github.blemale.scaffeine.{ Cache, Scaffeine }

object Palantir:

  private class Channel:

    private val members: Cache[UserId, Unit] = Scaffeine()
      .expireAfterWrite(7.seconds)
      .build[UserId, Unit]()

    def add(userId: UserId) = members.put(userId, ())

    def userIds = members.asMap().keys

  private val channels: Cache[RoomId, Channel] = Scaffeine()
    .expireAfterWrite(1.minute)
    .build[RoomId, Channel]()

  def respondToPing(roomId: RoomId, user: UserId): ipc.ClientIn.Palantir =
    val channel = channels.get(roomId, _ => new Channel)
    channel.add(user)
    Monitor.palantirChannels.update(channels.estimatedSize().toDouble)
    ipc.ClientIn.Palantir(channel.userIds.filter(user.!=))
