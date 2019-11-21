package lila.ws

import akka.actor.typed.{ ActorSystem, Behavior }
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import io.netty.handler.codec.http.HttpResponseStatus
import javax.inject._
import scala.concurrent.duration._
import scala.concurrent.{ Future, ExecutionContext }

import util.RequestHeader
import util.Util.parseIntOption

@Singleton
final class Controller @Inject() (
    config: Config,
    mongo: Mongo,
    auth: Auth,
    lila: Lila,
    lilaHandler: LilaHandler,
    services: Services,
    monitor: Monitor
)(implicit
    ec: ExecutionContext,
    system: ActorSystem[Clients.Control]
) {

  import Controller._
  import ClientActor.{ Deps, Req }

  private val logger = Logger(getClass)

  def site(req: RequestHeader, emit: ClientEmit) = WebSocket(req) { sri => user =>
    Future successful endpoint(
      SiteClientActor start {
        Deps(emit, Req(req, sri, user), services)
      },
      maxCredits = 50,
      interval = 20.seconds,
      name = s"site ${req.name}"
    )
  }

  def lobby(req: RequestHeader, emit: ClientEmit) = WebSocket(req) { sri => user =>
    Future successful endpoint(
      LobbyClientActor start {
        Deps(emit, Req(req, sri, user), services)
      },
      maxCredits = 30,
      interval = 30.seconds,
      name = s"lobby ${req.name}"
    )
  }

  def simul(id: Simul.ID, req: RequestHeader, emit: ClientEmit) = WebSocket(req) { sri => user =>
    mongo.simulExists(id) zip mongo.isTroll(user) map {
      case (true, isTroll) => endpoint(
        SimulClientActor.start(RoomActor.State(RoomId(id), isTroll), fromVersion(req)) {
          Deps(emit, Req(req, sri, user), services)
        },
        maxCredits = 30,
        interval = 20.seconds,
        name = s"simul ${req.name}"
      )
      case _ => notFound
    }
  }

  def tournament(id: Tour.ID, req: RequestHeader, emit: ClientEmit) = WebSocket(req) { sri => user =>
    mongo.tourExists(id) zip mongo.isTroll(user) map {
      case (true, isTroll) => endpoint(
        TourClientActor.start(RoomActor.State(RoomId(id), isTroll), fromVersion(req)) {
          Deps(emit, Req(req, sri, user), services)
        },
        maxCredits = 30,
        interval = 20.seconds,
        name = s"tour ${req.name}"
      )
      case _ => notFound
    }
  }

  def study(id: Study.ID, req: RequestHeader, emit: ClientEmit) = WebSocket(req) { sri => user =>
    mongo.studyExistsFor(id, user) zip mongo.isTroll(user) map {
      case (true, isTroll) => endpoint(
        StudyClientActor.start(RoomActor.State(RoomId(id), isTroll), fromVersion(req)) {
          Deps(emit, Req(req, sri, user), services)
        },
        maxCredits = 60,
        interval = 15.seconds,
        name = s"study ${req.name}"
      )
      case _ => notFound
    }
  }

  def roundWatch(id: Game.Id, req: RequestHeader, emit: ClientEmit) = WebSocket(req) { sri => user =>
    mongo.gameExists(id) zip mongo.isTroll(user) map {
      case (true, isTroll) =>
        val userTv = req queryParameter "userTv" map UserTv.apply
        userTv foreach { tv =>
          lila.emit.round(ipc.LilaIn.UserTv(id, tv.value))
        }
        endpoint(
          RoundClientActor.start(RoomActor.State(RoomId(id), isTroll), None, userTv, fromVersion(req)) {
            Deps(emit, Req(req, sri, user), services)
          },
          maxCredits = 50,
          interval = 20.seconds,
          name = s"round/watch ${req.name}"
        )
      case _ => notFound
    }
  }

  def roundPlay(id: Game.FullId, req: RequestHeader, emit: ClientEmit) = WebSocket(req) { sri => user =>
    mongo.player(id, user) zip mongo.isTroll(user) map {
      case (Some(player), isTroll) => endpoint(
        RoundClientActor.start(
          RoomActor.State(RoomId(id.gameId), isTroll), Some(player), None, fromVersion(req)
        ) { Deps(emit, Req(req, sri, user), services) },
        maxCredits = 50,
        interval = 20.seconds,
        name = s"round/play ${req.name}"
      )
      case _ => notFound
    }
  }

  def challenge(id: Challenge.Id, req: RequestHeader, emit: ClientEmit) = WebSocket(req) { sri => user =>
    mongo challenger id map {
      _ map {
        case Challenge.Anon(secret) => auth sidFromReq req contains secret
        case Challenge.User(userId) => user.exists(_.id == userId)
      }
    } map {
      case None => notFound
      case Some(owner) => endpoint(
        ChallengeClientActor.start(RoomActor.State(RoomId(id), IsTroll(false)), owner, fromVersion(req)) {
          Deps(emit, Req(req, sri, user), services)
        },
        maxCredits = 50,
        interval = 30.seconds,
        name = s"challenge ${req.name}"
      )
    }
  }

  def api(req: RequestHeader, emit: ClientEmit) = Future successful {
    endpoint(
      SiteClientActor.start {
        Deps(emit, Req(req, Sri.random, None).copy(flag = Some(Flag.api)), services)
      },
      maxCredits = 50,
      interval = 20.seconds,
      name = s"api ${req.name}"
    )
  }

  private def WebSocket(req: RequestHeader)(f: Sri => Option[User] => Response): Response =
    CSRF.check(req) {
      ValidSri(req) { sri =>
        auth(req) flatMap f(sri)
      }
    }

  private def ValidSri(req: RequestHeader)(f: Sri => Response): Response = req.sri match {
    case Some(validSri) => f(validSri)
    case None =>
      // f(Sri.random)
      Future successful Left(HttpResponseStatus.BAD_REQUEST)
  }

  private object CSRF {

    val csrfOrigin = config.getString("csrf.origin")
    val appOrigins = Set(
      "ionic://localhost", // ios
      "capacitor://localhost", // capacitor (ios next)
      "http://localhost", // android
      "http://localhost:8080", // local dev
      "file://"
    )

    def check(req: RequestHeader)(f: => Response): Response =
      req.origin match {
        case None => f // for exotic clients and acid ape chess
        case Some(origin) if origin == csrfOrigin || appOrigins(origin) => f
        case Some(origin) =>
          logger.info(s"""CSRF origin: "$origin" ${req.name}""")
          Future successful Left(HttpResponseStatus.FORBIDDEN)
      }
  }

  private def notFound = Left(HttpResponseStatus.NOT_FOUND)

  private def fromVersion(req: RequestHeader): Option[SocketVersion] =
    req queryParameter "v" flatMap parseIntOption map SocketVersion.apply
}

object Controller {

  val logger = Logger(getClass)

  final class Endpoint(val behavior: ClientBehavior, val rateLimit: RateLimit)

  def endpoint(
    behavior: ClientBehavior,
    maxCredits: Int,
    interval: FiniteDuration,
    name: String
  ) = Right(new Endpoint(
    behavior,
    new RateLimit(
      maxCredits = 50,
      interval = 20.seconds,
      name = name
    )
  ))

  type Response = Future[Either[HttpResponseStatus, Endpoint]]
}
