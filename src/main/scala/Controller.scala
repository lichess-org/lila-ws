package lila.ws

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import io.netty.handler.codec.http.HttpResponseStatus
import util.RequestHeader

final class Controller(
    config: Config,
    mongo: Mongo,
    auth: Auth,
    services: Services
)(using Executor):

  import Controller.*
  import ClientActor.{ Deps, Req }

  private val logger = Logger(getClass)

  def site(req: RequestHeader) =
    WebSocket(req) { sri => user =>
      Future successful siteEndpoint(req, sri, user)
    }

  private def siteEndpoint(req: RequestHeader, sri: Sri, user: Option[User.Id]) =
    endpoint(
      name = "site",
      behavior = emit =>
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
        behavior = emit =>
          LobbyClientActor start {
            Deps(emit, Req(req, sri, user), services)
          },
        credits = 30,
        interval = 30.seconds
      )
    }

  def simul(id: Simul.Id, req: RequestHeader) =
    WebSocket(req) { sri => user =>
      mongo.simulExists(id) zip mongo.troll.is(user) map {
        case (true, isTroll) =>
          endpoint(
            name = "simul",
            behavior = emit =>
              SimulClientActor.start(RoomActor.State(id.into(RoomId), isTroll), fromVersion(req)) {
                Deps(emit, Req(req, sri, user), services)
              },
            credits = 30,
            interval = 20.seconds
          )
        case _ => notFound
      }
    }

  def tournament(id: Tour.Id, req: RequestHeader) =
    WebSocket(req) { sri => user =>
      mongo.tourExists(id) zip mongo.troll.is(user) map {
        case (true, isTroll) =>
          endpoint(
            name = "tour",
            behavior = emit =>
              TourClientActor.start(RoomActor.State(id into RoomId, isTroll), fromVersion(req)) {
                Deps(emit, Req(req, sri, user), services)
              },
            credits = 30,
            interval = 20.seconds
          )
        case _ => notFound
      }
    }

  def study(id: Study.Id, req: RequestHeader) =
    WebSocket(req) { sri => user =>
      mongo.studyExistsFor(id, user) zip mongo.troll.is(user) map {
        case (true, isTroll) =>
          endpoint(
            name = "study",
            behavior = emit =>
              StudyClientActor.start(RoomActor.State(id into RoomId, isTroll), fromVersion(req)) {
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
          val userTv = UserTv.from(req queryParameter "userTv")
          endpoint(
            name = "round/watch",
            behavior = emit =>
              RoundClientActor
                .start(RoomActor.State(id.into(RoomId), isTroll), None, userTv, fromVersion(req)) {
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
            behavior = emit =>
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
            behavior = emit =>
              ChallengeClientActor
                .start(RoomActor.State(id into RoomId, IsTroll(false)), owner, fromVersion(req)) {
                  Deps(emit, Req(req, sri, user), services)
                },
            credits = 50,
            interval = 30.seconds
          )
      }
    }

  def team(id: Team.Id, req: RequestHeader) =
    WebSocket(req) { sri => user =>
      mongo.teamView(id, user) zip mongo.troll.is(user) map { case (view, isTroll) =>
        if (view.exists(_.yes))
          endpoint(
            name = "team",
            behavior = emit =>
              TeamClientActor.start(RoomActor.State(id into RoomId, isTroll), fromVersion(req)) {
                Deps(emit, Req(req, sri, user), services)
              },
            credits = 30,
            interval = 20.seconds
          )
        else siteEndpoint(req, sri, user)
      }
    }

  def swiss(id: Swiss.Id, req: RequestHeader) =
    WebSocket(req) { sri => user =>
      mongo.swissExists(id) zip mongo.troll.is(user) map {
        case (true, isTroll) =>
          endpoint(
            name = "swiss",
            behavior = emit =>
              SwissClientActor.start(RoomActor.State(id into RoomId, isTroll), fromVersion(req)) {
                Deps(emit, Req(req, sri, user), services)
              },
            credits = 30,
            interval = 20.seconds
          )
        case _ => notFound
      }
    }

  def racer(id: Racer.Id, req: RequestHeader) =
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
              behavior = emit =>
                RacerClientActor.start(RoomActor.State(id into RoomId, IsTroll(false)), pid) {
                  Deps(emit, Req(req, sri, user), services)
                },
              credits = 30,
              interval = 15.seconds
            )
      }
    }

  def api(req: RequestHeader) =
    CSRF.api(req) {
      Future successful endpoint(
        name = "api",
        behavior = emit =>
          SiteClientActor.start {
            Deps(emit, Req(req, Sri.random, None).copy(flag = Some(Flag.api)), services)
          },
        credits = 50,
        interval = 20.seconds
      )
    }

  private def WebSocket(req: RequestHeader)(f: Sri => Option[User.Id] => Response): Response =
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
    val apiOrigins = Set("https://www.lichess4545.com")

    def check(req: RequestHeader)(f: => Response): Response =
      req.origin match
        case None => f // for exotic clients and acid ape chess
        case Some(origin) if origin == csrfOrigin || appOrigins(origin) => f
        case _                                                          => block(req)

    def api(req: RequestHeader)(f: => Response): Response =
      req.origin match
        case None                               => f
        case Some(origin) if apiOrigins(origin) => f
        case _                                  => block(req)

    private def block(req: RequestHeader): Response =
      logger.debug(s"""CSRF origin: "${req.origin | "?"}" ${req.name}""")
      Future successful Left(HttpResponseStatus.FORBIDDEN)

  private def notFound = Left(HttpResponseStatus.NOT_FOUND)

  private def fromVersion(req: RequestHeader): Option[SocketVersion] =
    SocketVersion.from(req queryParameter "v" flatMap (_.toIntOption))

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
      Endpoint(
        behavior,
        RateLimit(
          maxCredits = credits,
          intervalMillis = interval.toMillis.toInt,
          name = name
        )
      )
    )

  type Response = Future[Either[HttpResponseStatus, Endpoint]]
