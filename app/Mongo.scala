package lila.ws

import javax.inject._
import play.api.Configuration
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.{ Cursor, DefaultDB, MongoConnection, MongoDriver }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
final class Mongo @Inject() (config: Configuration)(implicit executionContext: ExecutionContext) {

  private val uri = config.get[String]("mongo.uri")

  private val driver = MongoDriver()
  private val parsedUri = MongoConnection.parseURI(uri)
  private val connection = Future.fromTry(parsedUri.flatMap(driver.connection(_, true)))

  private def db: Future[DefaultDB] = connection.flatMap(_.database("lila"))
  private def securityColl = db.map(_.collection("security"))

  def security[A](f: BSONCollection => Future[A]): Future[A] = securityColl flatMap f
}
