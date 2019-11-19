package lila.ws

import javax.inject._

@Singleton
final class Services @Inject() (
    lilaRedis: Lila,
    val users: Users
) {
  def lila = lilaRedis.emit
}
