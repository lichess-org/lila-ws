package lila.ws

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import io.netty.handler.codec.http.HttpResponseStatus
import scala.concurrent.duration.*
import scala.concurrent.{ ExecutionContext, Future }
import util.RequestHeader

final class Controller(
    config: Config,
    mongo: Mongo,
    auth: Auth,
    services: Services
)(using ec: ExecutionContext):

  import Controller.*
  import ClientActor.{ Deps, Req }

  private val logger = Logger(getClass)

  def site(req: RequestHeader) =
    WebSocket(req) { sri => user =>
      Future successful siteEndpoint(req, sri, user)
    }

  private def siteEndpoint(req: RequestHeader, sri: Sri, user: Option[UserId]) =
    endpoint(
      name = "site",
      behavior = (emit: ClientEmit) =>
        SiteClientActor start {
          Deps(emit, Req(req, sri, user), services)
        },
      credits = 50,
      interval = 20.seconds
    )

  def lobby(req: RequestHeader) =
    WebSocket(req) { sri => user =>
      Future successful endpoint(
        name = "lobby",
        behavior = (emit: ClientEmit) =>
          LobbyClientActor start {
            Deps(emit, Req(req, sri, user), services)
          },
        credits = 30,
        interval = 30.seconds
      )
    }

  def simul(id: Simul.ID, req: RequestHeader) =
    WebSocket(req) { sri => user =>
      mongo.simulExists(id) zip mongo.troll.is(user) map {
        case (true, isTroll) =>
          endpoint(
            name = "simul",
            behavior = (emit: ClientEmit) =>
              SimulClientActor.start(RoomActor.State(RoomId(id), isTroll), fromVersion(req)) {
                Deps(emit, Req(req, sri, user), services)
              },
            credits = 30,
            interval = 20.seconds
          )
        case _ => notFound
      }
    }

  def tournament(id: Tour.ID, req: RequestHeader) =
    WebSocket(req) { sri => user =>
      mongo.tourExists(id) zip mongo.troll.is(user) map {
        case (true, isTroll) =>
          endpoint(
            name = "tour",
            behavior = (emit: ClientEmit) =>
              TourClientActor.start(RoomActor.State(RoomId(id), isTroll), fromVersion(req)) {
                Deps(emit, Req(req, sri, user), services)
              },
            credits = 30,
            interval = 20.seconds
          )
        case _ => notFound
      }
    }

  def study(id: Study.ID, req: RequestHeader) =
    WebSocket(req) { sri => user =>
      mongo.studyExistsFor(id, user) zip mongo.troll.is(user) map {
        case (true, isTroll) =>
          endpoint(
            name = "study",
            behavior = (emit: ClientEmit) =>
              StudyClientActor.start(RoomActor.State(RoomId(id), isTroll), fromVersion(req)) {
                Deps(emit, Req(req, sri, user), services)
              },
            credits = 60,
            interval = 15.seconds
          )
        case _ => notFound
      }
    }

  def roundWatch(id: Game.Id, req: RequestHeader) =
    WebSocket(req) { sri => user =>
      mongo.gameExists(id) zip mongo.troll.is(user) map {
        case (true, isTroll) =>
          val userTv = req queryParameter "userTv" map UserTv.apply
          endpoint(
            name = "round/watch",
            behavior = (emit: ClientEmit) =>
              RoundClientActor
                .start(RoomActor.State(RoomId.ofGame(id), isTroll), None, userTv, fromVersion(req)) {
                  Deps(emit, Req(req, sri, user), services)
                },
            credits = 50,
            interval = 20.seconds
          )
        case _ => notFound
      }
    }

  def roundPlay(id: Game.FullId, req: RequestHeader) =
    WebSocket(req) { sri => user =>
      mongo.player(id, user) zip mongo.troll.is(user) map {
        case (Some(player), isTroll) =>
          endpoint(
            name = "round/play",
            behavior = (emit: ClientEmit) =>
              RoundClientActor.start(
                RoomActor.State(RoomId.ofPlayer(id), isTroll),
                Some(player),
                None,
                fromVersion(req)
              ) { Deps(emit, Req(req, sri, user), services) },
            credits = 100,
            interval = 20.seconds
          )
        case _ => notFound
      }
    }

  def challenge(id: Challenge.Id, req: RequestHeader) =
    WebSocket(req) { sri => user =>
      mongo challenger id map {
        _ map {
          case Challenge.Challenger.Anon(secret) => Auth sidFromReq req contains secret
          case Challenge.Challenger.User(userId) => user.contains(userId)
          case Challenge.Challenger.Open         => false
        }
      } map {
        case None => notFound
        case Some(owner) =>
          endpoint(
            name = "challenge",
            behavior = (emit: ClientEmit) =>
              ChallengeClientActor
                .start(RoomActor.State(RoomId.ofChallenge(id), IsTroll(false)), owner, fromVersion(req)) {
                  Deps(emit, Req(req, sri, user), services)
                },
            credits = 50,
            interval = 30.seconds
          )
      }
    }

  def team(id: Team.ID, req: RequestHeader) =
    WebSocket(req) { sri => user =>
      mongo.teamView(id, user) zip mongo.troll.is(user) map { case (view, isTroll) =>
        if (view.exists(_.hasChat))
          endpoint(
            name = "team",
            behavior = (emit: ClientEmit) =>
              TeamClientActor.start(RoomActor.State(RoomId(id), isTroll), fromVersion(req)) {
                Deps(emit, Req(req, sri, user), services)
              },
            credits = 30,
            interval = 20.seconds
          )
        else siteEndpoint(req, sri, user)
      }
    }

  def swiss(id: Swiss.ID, req: RequestHeader) =
    WebSocket(req) { sri => user =>
      mongo.swissExists(id) zip mongo.troll.is(user) map {
        case (true, isTroll) =>
          endpoint(
            name = "swiss",
            behavior = (emit: ClientEmit) =>
              SwissClientActor.start(RoomActor.State(RoomId(id), isTroll), fromVersion(req)) {
                Deps(emit, Req(req, sri, user), services)
              },
            credits = 30,
            interval = 20.seconds
          )
        case _ => notFound
      }
    }

  def racer(id: Swiss.ID, req: RequestHeader) =
    WebSocket(req) { sri => user =>
      Future successful {
        user.match {
          case Some(u) => Option(Racer.PlayerId.User(u))
          case None    => Auth.sidFromReq(req) map Racer.PlayerId.Anon.apply
        }.match
          case None => notFound
          case Some(pid) =>
            endpoint(
              name = "racer",
              behavior = (emit: ClientEmit) =>
                RacerClientActor.start(RoomActor.State(RoomId(id), IsTroll(false)), pid) {
                  Deps(emit, Req(req, sri, user), services)
                },
              credits = 30,
              interval = 15.seconds
            )
      }
    }

  def api(req: RequestHeader) =
    Future successful endpoint(
      name = "api",
      behavior = (emit: ClientEmit) =>
        SiteClientActor.start {
          Deps(emit, Req(req, Sri.random, None).copy(flag = Some(Flag.api)), services)
        },
      credits = 50,
      interval = 20.seconds
    )

  private def WebSocket(req: RequestHeader)(f: Sri => Option[UserId] => Response): Response =
    CSRF.check(req) {
      ValidSri(req) { sri =>
        auth(req) flatMap f(sri)
      }
    }

  private def ValidSri(req: RequestHeader)(f: Sri => Response): Response =
    req.sri match
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

    def check(req: RequestHeader)(f: => Response): Response =
      req.origin match
        case None => f // for exotic clients and acid ape chess
        case Some(origin) if origin == csrfOrigin || appOrigins(origin) => f
        case Some(origin) =>
          logger.debug(s"""CSRF origin: "$origin" ${req.name}""")
          Future successful Left(HttpResponseStatus.FORBIDDEN)

  private def notFound = Left(HttpResponseStatus.NOT_FOUND)

  private def fromVersion(req: RequestHeader): Option[SocketVersion] =
    req queryParameter "v" flatMap (_.toIntOption) map SocketVersion.apply

object Controller:

  val logger = Logger(getClass)

  final class Endpoint(val behavior: ClientEmit => ClientBehavior, val rateLimit: RateLimit)
  def endpoint(
      name: String,
      behavior: ClientEmit => ClientBehavior,
      credits: Int,
      interval: FiniteDuration
  ) =
    Monitor.connection open name
    Right(
      new Endpoint(
        behavior,
        new RateLimit(
          maxCredits = credits,
          intervalMillis = interval.toMillis.toInt,
          name = name
        )
      )
    )

  type Response = Future[Either[HttpResponseStatus, Endpoint]]
