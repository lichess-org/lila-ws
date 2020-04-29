package lila.ws

import scala.concurrent.duration._
import com.github.blemale.scaffeine.{ Cache, Scaffeine }

object Palantir {

  private class Channel {

    private val members: Cache[User.ID, Unit] = Scaffeine()
      .expireAfterWrite(7.seconds)
      .build[User.ID, Unit]

    def add(userId: User.ID) = members.put(userId, ())

    def userIds = members.asMap.keys
  }

  private val channels: Cache[RoomId, Channel] = Scaffeine()
    .expireAfterWrite(1.minute)
    .build[RoomId, Channel]

  def respondToPing(roomId: RoomId, user: User): ipc.ClientIn.Palantir = {
    val channel = channels.get(roomId, _ => new Channel)
    channel.add(user.id)
    Monitor.palantirChannels.update(channels.estimatedSize.toInt)
    ipc.ClientIn.Palantir(channel.userIds.filter(user.id.!=))
  }
}
