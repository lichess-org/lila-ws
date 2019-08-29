package lichess.ws

import akka.actor._
import akka.stream.Materializer
import javax.inject._
import play.api.Configuration
import play.api.libs.streams.ActorFlow
import play.api.Logger
import play.api.mvc.RequestHeader
import scala.concurrent.{ ExecutionContext, Future }

import ipc._

@Singleton
final class SiteServer @Inject() (
    config: Configuration,
    auth: Auth,
    mongo: Mongo,
    redis: Redis,
    actors: Actors
)(implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer) {

  private val logger = Logger(getClass)
  private val bus = Bus(system)

  private val receive: Redis.Send = cmd =>
    LilaOut.parse(cmd).fold(logger.warn(s"LilaOut.parse $cmd")) {
      case m: LilaOut.Move =>
        // self.watched_games.write().insert(game.clone(), WatchedGame {
        //     fen: fen.to_owned(),
        //     lm: last_uci.to_owned()
        // });
        bus.publish(Bus.Msg(m, s"move:${m.game}"))

      // let by_game = self.by_game.read();
      // if let Some(entry) = by_game.get(&game) {
      //     let msg = Message::text(SocketIn::Fen {
      //         id: &game,
      //         fen,
      //         lm: last_uci,
      //     }.to_json_string());

      //     for sender in entry {
      //         if let Err(err) = sender.send(msg.clone()) {
      //             log::error!("failed to send fen: {:?}", err);
      //         }
      //     }
      // }
    }
  val send: Redis.Send = redis connect receive

  def connect(req: RequestHeader, sri: Sri) =
    auth(req) map { user =>
      ActorFlow actorRef { out =>
        SiteClientActor.props(out, sri, user, actors)
      }
    }
}
