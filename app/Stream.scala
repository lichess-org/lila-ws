package lila.ws

import akka.actor.ActorSystem
import akka.stream.scaladsl._
import io.lettuce.core.RedisURI
import javax.inject._
import play.api.Configuration
import scala.concurrent.{ ExecutionContext, Future }

import ipc._

@Singleton
final class Stream @Inject() (
    config: Configuration,
    graph: Graph
)(implicit
    ec: ExecutionContext,
    system: ActorSystem,
    mat: akka.stream.Materializer
) {

  lazy val lilaSite = new Lila(
    redisUri = RedisURI.create(config.get[String]("redis.uri")),
    chanIn = "site-in",
    chanOut = "site-out"
  )

  def start: Stream.Queues =
    graph.main(lilaSite.sink).run() match {
      case (lilaOut, toLila, toLag, toFen, toCount, toUser) =>
        lilaSite send LilaIn.DisconnectAll
        lilaSite plugSource lilaOut
        Stream.Queues(toLila, toLag, toFen, toCount, toUser)
    }
}

object Stream {

  case class Queues(
      lila: SourceQueue[LilaIn],
      lag: SourceQueue[LagSM.Input],
      fen: SourceQueue[FenSM.Input],
      count: SourceQueue[CountSM.Input],
      user: SourceQueue[UserSM.Input]
  ) {
    def apply[A](select: Queues => SourceQueue[A], msg: A): Unit =
      select(this) offer msg
  }
}
