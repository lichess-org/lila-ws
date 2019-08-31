package lila.ws

import akka.actor._
import akka.actor.typed.scaladsl.adapter._
import io.lettuce.core.RedisURI
import javax.inject._
import play.api.Configuration

import ipc._

@Singleton
final class Actors @Inject() (
    config: Configuration,
    system: ActorSystem
) {

  private val bus = Bus(system)
  private val typedSystem = system.toTyped

  val lilaSite: ActorRef = system.actorOf(Props(new LilaActor(
    redisUri = RedisURI.create(config.get[String]("redis.uri")),
    chanIn = "site-in",
    chanOut = "site-out",
    onReceive = {

      case move: LilaOut.Move =>
        fenActor ! FenActor.Move(move)

      case LilaOut.Mlat(millis) =>
        lagActor ! LagActor.Publish
        countActor ! CountActor.Publish
        bus.publish(ClientIn.Mlat(millis), _.mlat)

      case LilaOut.TellFlag(flag, json) =>
        bus.publish(ClientIn.AnyJson(json), _ flag flag)

      case LilaOut.TellUser(user, json) =>
        userActor ! UserActor.TellOne(user, ClientIn.AnyJson(json))

      case LilaOut.TellUsers(users, json) =>
        userActor ! UserActor.TellMany(users, ClientIn.AnyJson(json))

      case LilaOut.TellSri(sri, json) =>
        bus.publish(ClientIn.AnyJson(json), _ sri sri)

      case LilaOut.TellAll(json) =>
        bus.publish(ClientIn.AnyJson(json), _.all)

      case LilaOut.DisconnectUser(user) =>
        userActor ! UserActor.Kick(user)
    }
  )), "site")

  val countActor: akka.actor.typed.ActorRef[CountActor.Input] =
    system.spawn(CountActor.empty(lilaSite.!), "count")

  val lagActor: akka.actor.typed.ActorRef[LagActor.Input] =
    system.spawn(LagActor.empty(lilaSite.!), "lag")

  val fenActor: akka.actor.typed.ActorRef[FenActor.Input] =
    system.spawn(FenActor.empty(lilaSite.!), "fen")

  val userActor: akka.actor.typed.ActorRef[UserActor.Input] =
    system.spawn(UserActor.empty(lilaSite.!), "user")
}
