package lila.ws

import javax.inject._

@Singleton
final class Services @Inject() (
    lilaRedis: Lila,
    val users: Users,
    val fens: Fens,
    val roomCrowd: RoomCrowd,
    val roundCrowd: RoundCrowd,
    val keepAlive: KeepAlive
) {
  def lila = lilaRedis.emit
}
