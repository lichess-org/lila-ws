package lila.ws

import com.github.blemale.scaffeine.{ Cache, Scaffeine }

import ipc.*

object Tv:

  /* These 2 caches store ids of fast (bullet) and slow games that appeared on TV.
   * Players watching these games are notified whenever the relevant channel
   * switches to a new TV game. */

  private val fast: Cache[String, Boolean] = Scaffeine()
    .expireAfterWrite(10.minutes)
    .build[String, Boolean]()

  private val slow: Cache[String, Boolean] = Scaffeine()
    .expireAfterWrite(2.hours)
    .build[String, Boolean]()

  def select(out: LilaOut.TvSelect): Unit =
    val cliMsg = ClientIn.tvSelect(out.json)
    List(fast, slow).foreach: in =>
      in.asMap().keys foreach { gameId =>
        Bus.publish(_ room RoomId(gameId), cliMsg)
      }
    (if out.speed <= chess.Speed.Bullet then fast else slow).put(out.gameId.value, true)
    Bus.publish(_ tvChannels, cliMsg)

  def get(gameId: Game.Id): Boolean = get(gameId, fast) || get(gameId, slow)

  private def get(gameId: Game.Id, in: Cache[String, Boolean]): Boolean =
    isNotNull(in.underlying.getIfPresent(gameId.value))

  private inline def isNotNull[A](a: A) = a != null
