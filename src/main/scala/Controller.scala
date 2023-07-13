package lila.ws

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import io.netty.handler.codec.http.HttpResponseStatus
import util.RequestHeader
import ClientActor.{ Deps, Req }
import cats.syntax.option.*

final class Controller(
    config: Config,
    mongo: Mongo,
    auth: Auth,
    services: Services
)(using Executor):

  import Controller.*

  private val logger = Logger(getClass)

  def site(header: RequestHeader) =
    WebSocket(header): req =>
      Future successful siteEndpoint(req)

  private def siteEndpoint(req: Req) =
    endpoint(
      name = "site",
      behavior = emit =>
        SiteClientActor.start:
          Deps(emit, req, services)
      ,
      req.header,
      credits = 50,
      interval = 20.seconds
    )

  def lobby(header: RequestHeader) =
    WebSocket(header): req =>
      Future successful endpoint(
        name = "lobby",
        behavior = emit =>
          LobbyClientActor.start:
            Deps(emit, req, services)
        ,
        header,
        credits = 30,
        interval = 30.seconds
      )

  def simul(id: Simul.Id, header: RequestHeader) =
    WebSocket(header): req =>
      mongo.simulExists(id) zip mongo.troll.is(req.user) map:
        case (true, isTroll) =>
          endpoint(
            name = "simul",
            behavior = emit =>
              SimulClientActor.start(RoomActor.State(id.into(RoomId), isTroll), fromVersion(header)):
                Deps(emit, req, services)
            ,
            header,
            credits = 30,
            interval = 20.seconds
          )
        case _ => notFound

  def tournament(id: Tour.Id, header: RequestHeader) =
    WebSocket(header): req =>
      mongo.tourExists(id) zip mongo.troll.is(req.user) map:
        case (true, isTroll) =>
          endpoint(
            name = "tour",
            behavior = emit =>
              TourClientActor.start(RoomActor.State(id into RoomId, isTroll), fromVersion(header)):
                Deps(emit, req, services)
            ,
            header,
            credits = 30,
            interval = 20.seconds
          )
        case _ => notFound

  def study(id: Study.Id, header: RequestHeader) =
    WebSocket(header): req =>
      mongo.studyExistsFor(id, req.user) zip mongo.troll.is(req.user) map:
        case (true, isTroll) =>
          endpoint(
            name = "study",
            behavior = emit =>
              StudyClientActor.start(RoomActor.State(id into RoomId, isTroll), fromVersion(header)):
                Deps(emit, req, services)
            ,
            header,
            credits = 60,
            interval = 15.seconds
          )
        case _ => notFound

  private def roundFrom(id: Game.AnyId, req: Req): Future[Either[Option[SocketVersion], JsonString]] =
    if req.isLichessMobile then
      LilaRequest[JsonString](
        reqId => services.lila.round(ipc.LilaIn.RoundGet(reqId, id)),
        JsonString(_)
      ).map(Right(_))(parasitic)
    else Future.successful(Left(fromVersion(req.header)))

  def roundWatch(id: Game.Id, header: RequestHeader) =
    WebSocket(header): req =>
      mongo.gameExists(id) zip mongo.troll.is(req.user) zip roundFrom(id into Game.AnyId, req) map:
        case ((true, isTroll), from) =>
          val userTv = UserTv.from(header queryParameter "userTv")
          endpoint(
            name = "round/watch",
            behavior = emit =>
              RoundClientActor
                .start(RoomActor.State(id.into(RoomId), isTroll), None, userTv, from):
                  Deps(emit, req, services)
            ,
            header,
            credits = 50,
            interval = 20.seconds
          )
        case _ => notFound

  def roundPlay(id: Game.FullId, header: RequestHeader) =
    WebSocket(header): req =>
      mongo.player(id, req.user) zip mongo.troll.is(req.user) zip roundFrom(id into Game.AnyId, req) map:
        case ((Some(player), isTroll), from) =>
          endpoint(
            name = "round/play",
            behavior = emit =>
              RoundClientActor.start(
                RoomActor.State(RoomId.ofPlayer(id), isTroll),
                Some(player),
                None,
                from
              ) { Deps(emit, req, services) },
            header,
            credits = 100,
            interval = 20.seconds
          )
        case _ => notFound

  def challenge(id: Challenge.Id, header: RequestHeader) =
    WebSocket(header): req =>
      mongo
        .challenger(id)
        .map:
          _ map:
            case Challenge.Challenger.Anon(secret) => auth sidFromReq header contains secret
            case Challenge.Challenger.User(userId) => req.user.contains(userId)
            case Challenge.Challenger.Open         => false
        .map:
          _.fold(notFound): owner =>
            endpoint(
              name = "challenge",
              behavior = emit =>
                ChallengeClientActor
                  .start(RoomActor.State(id into RoomId, IsTroll(false)), owner, fromVersion(header)):
                    Deps(emit, req, services)
              ,
              header,
              credits = 50,
              interval = 30.seconds
            )

  def team(id: Team.Id, header: RequestHeader) =
    WebSocket(header): req =>
      mongo.teamView(id, req.user) zip mongo.troll.is(req.user) map { (view, isTroll) =>
        if view.exists(_.yes) then
          endpoint(
            name = "team",
            behavior = emit =>
              TeamClientActor.start(RoomActor.State(id into RoomId, isTroll), fromVersion(header)):
                Deps(emit, req, services)
            ,
            header,
            credits = 30,
            interval = 20.seconds
          )
        else siteEndpoint(req)
      }

  def swiss(id: Swiss.Id, header: RequestHeader) =
    WebSocket(header): req =>
      mongo.swissExists(id) zip mongo.troll.is(req.user) map:
        case (true, isTroll) =>
          endpoint(
            name = "swiss",
            behavior = emit =>
              SwissClientActor.start(RoomActor.State(id into RoomId, isTroll), fromVersion(header)):
                Deps(emit, req, services)
            ,
            header,
            credits = 30,
            interval = 20.seconds
          )
        case _ => notFound

  def racer(id: Racer.Id, header: RequestHeader) =
    WebSocket(header): req =>
      Future.successful:
        req.user
          .match
            case Some(u) => Option(Racer.PlayerId.User(u))
            case None    => auth.sidFromReq(header) map Racer.PlayerId.Anon.apply
          .match
            case None => notFound
            case Some(pid) =>
              endpoint(
                name = "racer",
                behavior = emit =>
                  RacerClientActor.start(RoomActor.State(id into RoomId, IsTroll(false)), pid):
                    Deps(emit, req, services)
                ,
                header,
                credits = 30,
                interval = 15.seconds
              )

  def api(header: RequestHeader) =
    val req = Req(header, Sri.random, None).copy(flag = Some(Flag.api))
    Future successful endpoint(
      name = "api",
      behavior = emit =>
        SiteClientActor.start:
          Deps(emit, req, services)
      ,
      header,
      credits = 50,
      interval = 30.seconds
    )

  private def WebSocket(req: RequestHeader)(f: Req => Response): Response =
    CSRF.check(req):
      ValidSri(req): sri =>
        auth(req).flatMap: authResult =>
          f(Req(req, sri, authResult))

  private def ValidSri(header: RequestHeader)(f: Sri => Response): Response =
    Sri from header.uncheckedSri match
      case Some(validSri) => f(validSri)
      case None           => Future successful Left(HttpResponseStatus.BAD_REQUEST)

  private object CSRF:

    val csrfOrigin = config.getString("csrf.origin")
    val appOrigins = Set(
      "ionic://localhost",     // ios
      "capacitor://localhost", // capacitor (ios next)
      "http://localhost",      // android
      "http://localhost:8080"  // local app dev
    )
    val apiOrigins = Set("https://www.lichess4545.com")

    def check(req: RequestHeader)(f: => Response): Response =
      req.origin match
        case None => f // for exotic clients and acid ape chess
        case Some(origin) if origin == csrfOrigin || appOrigins(origin) => f
        case _                                                          => block(req)

    private def block(req: RequestHeader): Response =
      logger.info(s"""CSRF origin: "${req.origin | "?"}" ${req.name}""")
      Future successful Left(HttpResponseStatus.FORBIDDEN)

  private def notFound = Left(HttpResponseStatus.NOT_FOUND)

  private def fromVersion(req: RequestHeader): Option[SocketVersion] =
    SocketVersion.from(req queryParameter "v" flatMap (_.toIntOption))

object Controller:

  val logger = Logger(getClass)

  final class Endpoint(
      val behavior: ClientEmit => ClientBehavior,
      val rateLimit: RateLimit,
      val header: RequestHeader
  )
  def endpoint(
      name: String,
      behavior: ClientEmit => ClientBehavior,
      header: RequestHeader,
      credits: Int,
      interval: FiniteDuration
  ): ResponseSync =
    Monitor.connection open name
    Right:
      Endpoint(
        behavior,
        RateLimit(
          maxCredits = credits,
          intervalMillis = interval.toMillis.toInt,
          name = name
        ),
        header
      )

  type ResponseSync = Either[HttpResponseStatus, Endpoint]
  type Response     = Future[ResponseSync]
