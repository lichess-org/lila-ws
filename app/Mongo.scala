package lila.ws

import javax.inject._
import org.joda.time.DateTime
import play.api.Configuration
import reactivemongo.api.bson._
import reactivemongo.api.bson.collection.BSONCollection
import reactivemongo.api.{ Cursor, DefaultDB, MongoConnection, MongoDriver, ReadConcern }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Try, Success }

@Singleton
final class Mongo @Inject() (config: Configuration)(implicit executionContext: ExecutionContext) {

  private val uri = config.get[String]("mongo.uri")

  private val driver = MongoDriver()
  private val parsedUri = MongoConnection.parseURI(uri)
  private val connection = Future.fromTry(parsedUri.flatMap(driver.connection(_, true)))

  private def db: Future[DefaultDB] = connection.flatMap(_.database("lichess"))
  private def collNamed(name: String) = db.map(_.collection(name))
  def securityColl = collNamed("security")
  def userColl = collNamed("user4")
  def coachColl = collNamed("coach")
  def streamerColl = collNamed("streamer")
  def simulColl = collNamed("simul")
  def tourColl = collNamed("tournament2")
  def tourPlayerColl = collNamed("tournament_player")
  def tourPairingColl = collNamed("tournament_pairing")
  def studyColl = collNamed("study")
  def gameColl = collNamed("game5")
  def challengeColl = collNamed("challenge")

  def security[A](f: BSONCollection => Future[A]): Future[A] = securityColl flatMap f
  def coach[A](f: BSONCollection => Future[A]): Future[A] = coachColl flatMap f
  def streamer[A](f: BSONCollection => Future[A]): Future[A] = streamerColl flatMap f
  def user[A](f: BSONCollection => Future[A]): Future[A] = userColl flatMap f

  def simulExists(id: Simul.ID): Future[Boolean] = simulColl flatMap idExists(id)

  def tourExists(id: Simul.ID): Future[Boolean] = tourColl flatMap idExists(id)

  def studyExists(id: Study.ID): Future[Boolean] = studyColl flatMap idExists(id)

  def gameExists(id: Game.Id): Future[Boolean] = gameColl flatMap idExists(id.value)

  def player(fullId: Game.FullId, user: Option[User]): Future[Option[Player]] = gameColl flatMap {
    _.find(
      selector = BSONDocument("_id" -> fullId.gameId.value),
      projection = Some(BSONDocument("is" -> true, "us" -> true, "tid" -> true))
    ).one[BSONDocument] map { docOpt =>
        for {
          doc <- docOpt
          playerIds <- doc.getAsOpt[String]("is")
          users = doc.getAsOpt[List[String]]("us") getOrElse Nil
          color <- {
            if (fullId.playerId.value == playerIds.take(4)) Some(chess.White)
            else if (fullId.playerId.value == playerIds.drop(4)) Some(chess.Black)
            else None
          }
          expectedUserId = color.fold(users.headOption, users.lift(1)).filter(_.nonEmpty)
          if user.map(_.id) == expectedUserId
          tourId = doc.getAsOpt[Tour.ID]("tid")
        } yield Player(fullId.playerId, color, tourId)
      }
  }

  def studyExistsFor(id: Simul.ID, user: Option[User]): Future[Boolean] = studyColl flatMap {
    exists(_, BSONDocument(
      "_id" -> id,
      user.fold(visibilityNotPrivate) { u =>
        BSONDocument(
          "$or" -> BSONArray(
            visibilityNotPrivate,
            BSONDocument(s"members.${u.id}" -> BSONDocument("$exists" -> true))
          )
        )
      }
    ))
  }

  def studyMembers(id: Study.ID): Future[Set[User.ID]] = studyColl flatMap {
    _.find(
      selector = BSONDocument("_id" -> id),
      projection = Some(BSONDocument("members" -> true))
    ).one[BSONDocument] map { docOpt =>
        for {
          doc <- docOpt
          members <- doc.getAsOpt[BSONDocument]("members")
        } yield members.elements.map { case BSONElement(key, _) => key }.toSet
      } map (_ getOrElse Set.empty)
  }

  def tournamentActiveUsers(tourId: Tour.ID): Future[Set[User.ID]] = tourPlayerColl flatMap {
    _.distinct[User.ID, Set](
      key = "uid",
      selector = Some(BSONDocument("tid" -> tourId, "w" -> BSONDocument("$ne" -> true))),
      readConcern = ReadConcern.Local,
      collation = None
    )
  }

  def tournamentPlayingUsers(tourId: Tour.ID): Future[Set[User.ID]] = tourPairingColl flatMap {
    _.distinct[User.ID, Set](
      key = "u",
      selector = Some(BSONDocument("tid" -> tourId, "s" -> BSONDocument("$lt" -> chess.Status.Mate.id))),
      readConcern = ReadConcern.Local,
      collation = None
    )
  }

  def challenger(challengeId: Challenge.Id): Future[Option[Challenge.Challenger]] = challengeColl flatMap {
    _.find(
      selector = BSONDocument("_id" -> challengeId.value),
      projection = Some(BSONDocument("challenger" -> true))
    ).one[BSONDocument] map { docOpt =>
        for {
          doc <- docOpt
          c <- doc.getAsOpt[BSONDocument]("challenger")
          anon = c.getAsOpt[String]("s") map Challenge.Anon.apply
          user = c.getAsOpt[String]("id") map Challenge.User.apply
          challenger <- anon orElse user
        } yield challenger
      }
  }

  private val visibilityNotPrivate = BSONDocument("visibility" -> BSONDocument("$ne" -> "private"))

  def isTroll(user: Option[User]): Future[IsTroll] =
    user.fold(Future successful IsTroll(false)) { u =>
      userColl flatMap { exists(_, BSONDocument("_id" -> u.id, "troll" -> true)) } map IsTroll.apply
    }

  object idFilter {
    import Mongo._
    val study: IdFilter = ids => studyColl flatMap filterIds(ids)
    val tour: IdFilter = ids => tourColl flatMap filterIds(ids)
    val simul: IdFilter = ids => simulColl flatMap filterIds(ids)
  }

  private def idExists(id: String)(coll: BSONCollection): Future[Boolean] =
    exists(coll, BSONDocument("_id" -> id))

  private def exists(coll: BSONCollection, selector: BSONDocument): Future[Boolean] =
    coll.count(
      selector = Some(selector),
      limit = None,
      skip = 0,
      hint = None,
      readConcern = ReadConcern.Local
    ).map(0 < _)

  private def filterIds(ids: Iterable[String])(coll: BSONCollection): Future[Set[String]] =
    coll.distinct[String, Set](
      key = "_id",
      selector = Some(BSONDocument("_id" -> BSONDocument("$in" -> ids))),
      readConcern = ReadConcern.Local,
      collation = None
    )
}

object Mongo {

  type IdFilter = Iterable[String] => Future[Set[String]]

  implicit val BSONDateTimeHandler = new BSONHandler[DateTime] {

    @inline def readTry(bson: BSONValue): Try[DateTime] =
      bson.asTry[BSONDateTime] map { dt => new DateTime(dt.value) }

    @inline def writeTry(date: DateTime) = Success(BSONDateTime(date.getMillis))
  }
}
