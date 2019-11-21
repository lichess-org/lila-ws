package lila.ws

import akka.actor.typed.{ ActorSystem, Behavior }
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import io.netty.handler.codec.http.HttpResponseStatus
import javax.inject._
import scala.concurrent.duration._
import scala.concurrent.{ Future, ExecutionContext }

import util.RequestHeader

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

  private val logger = Logger(getClass)

  def site(req: RequestHeader, emit: ClientEmit): Response =
    WebSocket(req) { sri =>
      auth(req) map { user =>
        endpoint(
          SiteClientActor.start {
            ClientActor.Deps(emit, ClientActor.Req(req, sri, user), services)
          },
          maxCredits = 50,
          interval = 20.seconds,
          name = s"site ${req.name}"
        )
      }
    }

  def api(req: RequestHeader, emit: ClientEmit): Response =
    Future successful {
      endpoint(
        SiteClientActor.start {
          ClientActor.Deps(emit, ClientActor.Req(req, Sri.random, None).copy(flag = Some(Flag.api)), services)
        },
        maxCredits = 50,
        interval = 20.seconds,
        name = s"api ${req.name}"
      )
    }

  private def WebSocket(req: RequestHeader)(f: Sri => Response): Response =
    CSRF.check(req) {
      ValidSri(req)(f)
    }

  private def ValidSri(req: RequestHeader)(f: Sri => Response): Response = req.sri match {
    case Some(validSri) => f(validSri)
    case None =>
      f(Sri.random)
    // Future successful Left(HttpResponseStatus.BAD_REQUEST)
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
