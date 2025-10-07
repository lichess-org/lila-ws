package lila.ws

import com.github.blemale.scaffeine.Cache
import com.typesafe.scalalogging.Logger
import scalalib.zeros.given

import ipc.ClientIn.LobbyPong
import ipc.{ LilaIn, ClientIn }

final class Lobby(lila: Lila, groupedWithin: util.GroupedWithin, tor: Tor)(using
    cacheApi: util.CacheApi
):

  private val lilaIn = lila.emit.lobby

  private val connect = groupedWithin[(Sri, Option[User.Id])](6, 479.millis): connects =>
    lilaIn(LilaIn.ConnectSris(connects))

  def onConnect(req: ClientActor.Req): Unit =
    connect(req.sri -> req.user)
    OldAppSriMemory.onConnect(req)

  val disconnect = groupedWithin[Sri](50, 487.millis) { sris => lilaIn(LilaIn.DisconnectSris(sris)) }

  object pong:

    private var value = LobbyPong(0, 0)

    def get() = value

    def update(members: Int, rounds: Int): Unit =
      value = LobbyPong(members, rounds)

  // the old app has a new SRI for each websocket connection.
  // if it disconnects while being paired,
  // then the new game `redirect` message is sent to the previous SRI that created the game,
  // and not to the new SRI that is currently connected.
  // The workaround is to remember previous SRIs of each user, so that new game messages
  // are sent to all their current and previous SRIs.
  object OldAppSriMemory:

    // old SRI -> new SRI
    private val sriChain: Cache[Sri, Sri] = cacheApi.notLoadingSync[Sri, Sri](8_192, "lobby.sriChain"):
      _.maximumSize(65_536).expireAfterWrite(3.minutes).build()

    // last known SRI of user
    private val userSri: Cache[User.Id, Sri] = cacheApi.notLoadingSync[User.Id, Sri](8_192, "lobby.userSri"):
      _.maximumSize(65_536).expireAfterWrite(3.minutes).build()

    def onConnect(req: ClientActor.Req): Unit = for
      user <- req.user
      if req.header.isLichobile
      prevSri = userSri.getIfPresent(user)
      if prevSri.forall(_ != req.sri)
    do
      prevSri.foreach: ps =>
        if sriChain.getIfPresent(req.sri).isDefined
        then Monitor.mobile.lobbySriChain.loopAvoided.increment()
        else sriChain.put(ps, req.sri)
      userSri.put(user, req.sri)

    // get new SRIs of old SRI, recursively (!)
    def allNewSris(fromSri: Sri): List[Sri] =
      fromSri :: sriChain.getIfPresent(fromSri).so(allNewSris)

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

    // prevent flood join by requiring that joined games be played
    private object PendingGamesPerIp:

      private val maxPendingGames = 5
      private val logger = Logger("PendingGamesPerIp")

      private val pendingGames: Cache[IpAddress, Set[Game.Id]] =
        cacheApi.notLoadingSync[IpAddress, Set[Game.Id]](8_192, "lobby.pendingGamesPerIp"):
          _.maximumSize(65_536).expireAfterWrite(1.hour).build()

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
