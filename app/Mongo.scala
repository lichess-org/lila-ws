package lila.ws

import javax.inject._
import org.joda.time.DateTime
import play.api.Configuration
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.{ Cursor, DefaultDB, MongoConnection, MongoDriver, ReadConcern }
import reactivemongo.bson._
import scala.concurrent.{ ExecutionContext, Future }

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

  def security[A](f: BSONCollection => Future[A]): Future[A] = securityColl flatMap f
  def coach[A](f: BSONCollection => Future[A]): Future[A] = coachColl flatMap f
  def streamer[A](f: BSONCollection => Future[A]): Future[A] = streamerColl flatMap f
  def simul[A](f: BSONCollection => Future[A]): Future[A] = simulColl flatMap f

  def simulExists(id: String): Future[Boolean] = simul(_.count(
    selector = Some(BSONDocument("_id" -> id)),
    limit = None,
    skip = 0,
    hint = None,
    readConcern = ReadConcern.Local
  ).map(0 < _))
}

object Mongo {

  implicit val BSONDateTimeHandler: BSONHandler[BSONDateTime, DateTime] =
    new BSONHandler[BSONDateTime, DateTime] {
      def read(time: BSONDateTime) = new DateTime(time.value)
      def write(jdtime: DateTime) = BSONDateTime(jdtime.getMillis)
    }
}
