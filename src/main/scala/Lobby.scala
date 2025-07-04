package lila.ws

import com.github.blemale.scaffeine.{ Cache, Scaffeine }
import com.typesafe.scalalogging.Logger
import scalalib.zeros.given

import ipc.ClientIn.LobbyPong
import ipc.{ LilaIn, ClientIn }

final class Lobby(lila: Lila, groupedWithin: util.GroupedWithin, tor: Tor):

  private val lilaIn = lila.emit.lobby

  val connect = groupedWithin[(Sri, Option[User.Id])](6, 479.millis) { connects =>
    lilaIn(LilaIn.ConnectSris(connects))
  }

  val disconnect = groupedWithin[Sri](50, 487.millis) { sris => lilaIn(LilaIn.DisconnectSris(sris)) }

  object pong:

    private var value = LobbyPong(0, 0)

    def get() = value

    def update(members: Int, rounds: Int): Unit =
      value = LobbyPong(members, rounds)

  object anonJoin:

    private val byIpRateLimit = RateLimitMap("lobby.join.ip", 250, 1.day, enforce = true)

    def canJoin(req: ClientActor.Req): Boolean =
      byIpRateLimit(req.ip.value) && {
        req.auth.isDefined || {
          !tor.isExitNode(req.ip) &&
          PendingGamesPerIp.canJoin(req.ip)
        }
      }

    def onRoundPlayConnect(req: ClientActor.Req, gameId: Game.Id): Unit =
      if req.auth.isEmpty then PendingGamesPerIp.registerPlayed(req.ip, gameId)

    def tapLobbyClientIn(req: ClientActor.Req, msg: ClientIn): Unit =
      if req.auth.isEmpty then
        msg.match
          case ClientIn.Payload(json) =>
            json.value.match
              case redirectToGameIdRegex(gameId) =>
                PendingGamesPerIp.redirectToGame(req.ip, Game.Id(gameId))
              case _ =>
          case _ =>

    private val redirectToGameIdRegex = """t":"redirect",.+"id":"(\w{8})""".r.unanchored

    private object PendingGamesPerIp:

      private val maxPendingGames = 5
      private val logger          = Logger("PendingGamesPerIp")

      private val pendingGames: Cache[IpAddress, Set[Game.Id]] = Scaffeine()
        .initialCapacity(8_192)
        .maximumSize(65_536)
        .expireAfterWrite(1.hour)
        .build()

      def canJoin(ip: IpAddress): Boolean =
        pendingGames.getIfPresent(ip) match
          case Some(pending) if pending.size >= maxPendingGames =>
            logger.info:
              s"Anon $ip can't join a lobby game, too many pending games: ${pending.mkString(", ")}"
            false
          case _ => true

      def redirectToGame(ip: IpAddress, gameId: Game.Id): Unit =
        pendingGames.put(ip, pendingGames.getIfPresent(ip).orZero + gameId)

      def registerPlayed(ip: IpAddress, gameId: Game.Id): Unit =
        pendingGames
          .getIfPresent(ip)
          .filter(_.contains(gameId))
          .foreach: pending =>
            val newPending = pending - gameId
            if newPending.isEmpty
            then pendingGames.invalidate(ip)
            else pendingGames.put(ip, newPending)
