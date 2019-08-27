package lichess.ws

import javax.inject._

import scala.concurrent.{ ExecutionContext, Future }

import reactivemongo.api.{ Cursor, DefaultDB, MongoConnection, MongoDriver }
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson.{
  BSONDocumentWriter,
  BSONDocumentReader,
  Macros,
  document
}

@Singleton
final class Mongo @Inject() ()(implicit executionContext: ExecutionContext) {

  val mongoUri = "mongodb://localhost:27017/lichess"

  val driver = MongoDriver()
  val parsedUri = MongoConnection.parseURI(mongoUri)
  val connection = Future.fromTry(parsedUri.flatMap(driver.connection(_, true)))

  def db: Future[DefaultDB] = connection.flatMap(_.database("lichess"))
  def securityColl = db.map(_.collection("security"))

  def security[A](f: BSONCollection => Future[A]): Future[A] = securityColl flatMap f
}
