package lila.ws

import com.github.blemale.scaffeine.{ Cache, Scaffeine }

object VoiceChat:

  private class Channel:

    private val members: Cache[User.Id, Unit] = Scaffeine()
      .expireAfterWrite(7.seconds)
      .build[User.Id, Unit]()

    def add(userId: User.Id) = members.put(userId, ())

    def userIds = members.asMap().keys

  private val channels: Cache[RoomId, Channel] = Scaffeine()
    .expireAfterWrite(1.minute)
    .build[RoomId, Channel]()

  def respondToPing(roomId: RoomId, user: User.Id): ipc.ClientIn.VoiceChat =
    val channel = channels.get(roomId, _ => new Channel)
    channel.add(user)
    Monitor.voiceChatChannels.update(channels.estimatedSize().toDouble)
    ipc.ClientIn.VoiceChat(channel.userIds.filter(user.!=))
