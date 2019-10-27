package lila.ws

import javax.inject._
import org.joda.time.DateTime
import play.api.Configuration
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.{ Cursor, DefaultDB, MongoConnection, MongoDriver, ReadConcern }
import reactivemongo.bson._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Success

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

  def security[A](f: BSONCollection => Future[A]): Future[A] = securityColl flatMap f
  def coach[A](f: BSONCollection => Future[A]): Future[A] = coachColl flatMap f
  def streamer[A](f: BSONCollection => Future[A]): Future[A] = streamerColl flatMap f
  def user[A](f: BSONCollection => Future[A]): Future[A] = userColl flatMap f

  def simulExists(id: Simul.ID): Future[Boolean] =
    simulColl flatMap { exists(_, BSONDocument("_id" -> id)) }

  def tourExists(id: Simul.ID): Future[Boolean] =
    tourColl flatMap { exists(_, BSONDocument("_id" -> id)) }

  def studyExists(id: Study.ID): Future[Boolean] =
    studyColl flatMap { exists(_, BSONDocument("_id" -> id)) }

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
          members <- doc.getAs[BSONDocument]("members")
        } yield members.stream.collect { case Success(BSONElement(key, _)) => key }.toSet
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

  private val visibilityNotPrivate = BSONDocument("visibility" -> BSONDocument("$ne" -> "private"))

  def isTroll(user: Option[User]): Future[IsTroll] =
    user.fold(Future successful IsTroll(false)) { u =>
      userColl flatMap { exists(_, BSONDocument("_id" -> u.id, "troll" -> true)) } map IsTroll.apply
    }

  private def exists(coll: BSONCollection, selector: BSONDocument): Future[Boolean] =
    coll.count(
      selector = Some(selector),
      limit = None,
      skip = 0,
      hint = None,
      readConcern = ReadConcern.Local
    ).map(0 < _)
}

object Mongo {

  implicit val BSONDateTimeHandler: BSONHandler[BSONDateTime, DateTime] =
    new BSONHandler[BSONDateTime, DateTime] {
      def read(time: BSONDateTime) = new DateTime(time.value)
      def write(jdtime: DateTime) = BSONDateTime(jdtime.getMillis)
    }
}
