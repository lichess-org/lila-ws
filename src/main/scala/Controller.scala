package lila.ws

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import io.netty.handler.codec.http.HttpResponseStatus
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

import util.RequestHeader

final class Controller(
    config: Config,
    mongo: Mongo,
    auth: Auth,
    services: Services
)(implicit ec: ExecutionContext) {

  import Controller._
  import ClientActor.{ Deps, Req }

  private val logger = Logger(getClass)

  def site(req: RequestHeader, emit: ClientEmit) =
    WebSocket(req) { sri => user =>
      Future successful endpoint(
        name = "site",
        behavior = SiteClientActor start {
          Deps(emit, Req(req, sri, user), services)
        },
        credits = 50,
        interval = 20.seconds
      )
    }

  def lobby(req: RequestHeader, emit: ClientEmit) =
    WebSocket(req) { sri => user =>
      Future successful endpoint(
        name = "lobby",
        behavior = LobbyClientActor start {
          Deps(emit, Req(req, sri, user), services)
        },
        credits = 30,
        interval = 30.seconds
      )
    }

  def simul(id: Simul.ID, req: RequestHeader, emit: ClientEmit) =
    WebSocket(req) { sri => user =>
      mongo.simulExists(id) zip mongo.troll.is(user) map {
        case (true, isTroll) =>
          endpoint(
            name = "simul",
            behavior = SimulClientActor.start(RoomActor.State(RoomId(id), isTroll), fromVersion(req)) {
              Deps(emit, Req(req, sri, user), services)
            },
            credits = 30,
            interval = 20.seconds
          )
        case _ => notFound
      }
    }

  def tournament(id: Tour.ID, req: RequestHeader, emit: ClientEmit) =
    WebSocket(req) { sri => user =>
      mongo.tourExists(id) zip mongo.troll.is(user) map {
        case (true, isTroll) =>
          endpoint(
            name = "tour",
            behavior = TourClientActor.start(RoomActor.State(RoomId(id), isTroll), fromVersion(req)) {
              Deps(emit, Req(req, sri, user), services)
            },
            credits = 30,
            interval = 20.seconds
          )
        case _ => notFound
      }
    }

  def study(id: Study.ID, req: RequestHeader, emit: ClientEmit) =
    WebSocket(req) { sri => user =>
      mongo.studyExistsFor(id, user) zip mongo.troll.is(user) map {
        case (true, isTroll) =>
          endpoint(
            name = "study",
            behavior = StudyClientActor.start(RoomActor.State(RoomId(id), isTroll), fromVersion(req)) {
              Deps(emit, Req(req, sri, user), services)
            },
            credits = 60,
            interval = 15.seconds
          )
        case _ => notFound
      }
    }

  def roundWatch(id: Game.Id, req: RequestHeader, emit: ClientEmit) =
    WebSocket(req) { sri => user =>
      mongo.gameExists(id) zip mongo.troll.is(user) map {
        case (true, isTroll) =>
          val userTv = req queryParameter "userTv" map UserTv.apply
          endpoint(
            name = "round/watch",
            behavior = RoundClientActor
              .start(RoomActor.State(RoomId(id), isTroll), None, userTv, fromVersion(req)) {
                Deps(emit, Req(req, sri, user), services)
              },
            credits = 50,
            interval = 20.seconds
          )
        case _ => notFound
      }
    }

  def roundPlay(id: Game.FullId, req: RequestHeader, emit: ClientEmit) =
    WebSocket(req) { sri => user =>
      mongo.player(id, user) zip mongo.troll.is(user) map {
        case (Some(player), isTroll) =>
          endpoint(
            name = "round/play",
            behavior = RoundClientActor.start(
              RoomActor.State(RoomId(id.gameId), isTroll),
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

  def challenge(id: Challenge.Id, req: RequestHeader, emit: ClientEmit) =
    WebSocket(req) { sri => user =>
      mongo challenger id map {
        _ map {
          case Challenge.Anon(secret) => auth sidFromReq req contains secret
          case Challenge.User(userId) => user.exists(_.id == userId)
          case Challenge.Open         => false
        }
      } map {
        case None => notFound
        case Some(owner) =>
          endpoint(
            name = "challenge",
            behavior = ChallengeClientActor
              .start(RoomActor.State(RoomId(id), IsTroll(false)), owner, fromVersion(req)) {
                Deps(emit, Req(req, sri, user), services)
              },
            credits = 50,
            interval = 30.seconds
          )
      }
    }

  def team(id: Team.ID, req: RequestHeader, emit: ClientEmit) =
    WebSocket(req) { sri => user =>
      mongo.teamExists(id) zip mongo.troll.is(user) map {
        case (true, isTroll) =>
          endpoint(
            name = "team",
            behavior = TeamClientActor.start(RoomActor.State(RoomId(id), isTroll), fromVersion(req)) {
              Deps(emit, Req(req, sri, user), services)
            },
            credits = 30,
            interval = 20.seconds
          )
        case _ => notFound
      }
    }

  def swiss(id: Swiss.ID, req: RequestHeader, emit: ClientEmit) =
    WebSocket(req) { sri => user =>
      mongo.swissExists(id) zip mongo.troll.is(user) map {
        case (true, isTroll) =>
          endpoint(
            name = "swiss",
            behavior = SwissClientActor.start(RoomActor.State(RoomId(id), isTroll), fromVersion(req)) {
              Deps(emit, Req(req, sri, user), services)
            },
            credits = 30,
            interval = 20.seconds
          )
        case _ => notFound
      }
    }

  def api(req: RequestHeader, emit: ClientEmit) =
    Future successful {
      endpoint(
        name = "api",
        behavior = SiteClientActor.start {
          Deps(emit, Req(req, Sri.random, None).copy(flag = Some(Flag.api)), services)
        },
        credits = 50,
        interval = 20.seconds
      )
    }

  private def WebSocket(req: RequestHeader)(f: Sri => Option[User] => Response): Response =
    CSRF.check(req) {
      ValidSri(req) { sri =>
        auth(req) flatMap f(sri)
      }
    }

  private def ValidSri(req: RequestHeader)(f: Sri => Response): Response =
    req.sri match {
      case Some(validSri) => f(validSri)
      case None           => Future successful Left(HttpResponseStatus.BAD_REQUEST)
    }

  private object CSRF {

    val csrfOrigin = config.getString("csrf.origin")
    val appOrigins = Set(
      "ionic://localhost",     // ios
      "capacitor://localhost", // capacitor (ios next)
      "http://localhost",      // android
      "http://localhost:8080", // local dev
      "http://localhost:9663", // lila dev
      "http://l.org",          // lila dev
      "file://"
    )

    def check(req: RequestHeader)(f: => Response): Response =
      req.origin match {
        case None                                                       => f // for exotic clients and acid ape chess
        case Some(origin) if origin == csrfOrigin || appOrigins(origin) => f
        case Some(origin) =>
          logger.debug(s"""CSRF origin: "$origin" ${req.name}""")
          Future successful Left(HttpResponseStatus.FORBIDDEN)
      }
  }

  private def notFound = Left(HttpResponseStatus.NOT_FOUND)

  private def fromVersion(req: RequestHeader): Option[SocketVersion] =
    req queryParameter "v" flatMap (_.toIntOption) map SocketVersion.apply
}

object Controller {

  val logger = Logger(getClass)

  final class Endpoint(val behavior: ClientBehavior, val rateLimit: RateLimit)
  def endpoint(
      name: String,
      behavior: ClientBehavior,
      credits: Int,
      interval: FiniteDuration
  ) = {
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
  }

  type Response = Future[Either[HttpResponseStatus, Endpoint]]
}
