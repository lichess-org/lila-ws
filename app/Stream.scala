package lila.ws

import akka.actor.ActorSystem
import akka.stream.scaladsl._
import io.lettuce.core.RedisURI
import javax.inject._
import play.api.Configuration
import scala.concurrent.{ ExecutionContext, Future }

import ipc._

@Singleton
final class Stream @Inject() ()(implicit
    ec: ExecutionContext,
    system: ActorSystem,
    mat: akka.stream.Materializer,
    config: Configuration,
    graph: Graph
) {

  lazy val lilaSite = new Lila(
    redisUri = RedisURI.create(config.get[String]("redis.uri")),
    chanIn = "site-in",
    chanOut = "site-out"
  )

  def start: Stream.Queues =
    graph.main(lilaSite.sink).run() match {
      case (lilaOut, clientToLila, clientToLag, clientToFen, clientToCount, clientToUser) =>
        lilaSite source lilaOut
        Stream.Queues(clientToLila, clientToLag, clientToFen, clientToCount, clientToUser)
    }
}

object Stream {

  case class Queues(
      clientToLila: SourceQueue[LilaIn],
      clientToLag: SourceQueue[LagSM.Input],
      clientToFen: SourceQueue[FenSM.Input],
      clientToCount: SourceQueue[CountSM.Input],
      clientToUser: SourceQueue[UserSM.Input]
  ) {
    def lila(in: LilaIn): Unit = clientToLila offer in
    def lag(in: LagSM.Input): Unit = clientToLag offer in
    def fen(in: FenSM.Input): Unit = clientToFen offer in
    def count(in: CountSM.Input): Unit = clientToCount offer in
    def user(in: UserSM.Input): Unit = clientToUser offer in
  }
}
