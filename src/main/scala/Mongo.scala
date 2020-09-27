package lila.ws

import chess.Color
import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import com.typesafe.config.Config
import org.joda.time.DateTime
import reactivemongo.api.bson._
import reactivemongo.api.bson.collection.BSONCollection
import reactivemongo.api.{ AsyncDriver, DB, MongoConnection, ReadConcern, ReadPreference }
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.parasitic
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Try }

final class Mongo(config: Config)(implicit executionContext: ExecutionContext) {

  private val uri = config.getString("mongo.uri")

  private val driver = new AsyncDriver(Some(config.getConfig("reactivemongo")))

  private val connection =
    MongoConnection.fromString(uri) flatMap { parsedUri =>
      driver.connect(parsedUri).map(_ -> parsedUri.db)
    }
  private def db: Future[DB] =
    connection flatMap { case (conn, dbName) =>
      conn database dbName.getOrElse("lichess")
    }

  private def collNamed(name: String) = db.map(_ collection name)(parasitic)
  def securityColl                    = collNamed("security")
  def userColl                        = collNamed("user4")
  def coachColl                       = collNamed("coach")
  def streamerColl                    = collNamed("streamer")
  def simulColl                       = collNamed("simul")
  def tourColl                        = collNamed("tournament2")
  def tourPlayerColl                  = collNamed("tournament_player")
  def tourPairingColl                 = collNamed("tournament_pairing")
  def studyColl                       = collNamed("study")
  def gameColl                        = collNamed("game5")
  def challengeColl                   = collNamed("challenge")
  def relationColl                    = collNamed("relation")
  def teamColl                        = collNamed("team")
  def swissColl                       = collNamed("swiss")

  def security[A](f: BSONCollection => Future[A]): Future[A] = securityColl flatMap f
  def coach[A](f: BSONCollection => Future[A]): Future[A]    = coachColl flatMap f
  def streamer[A](f: BSONCollection => Future[A]): Future[A] = streamerColl flatMap f
  def user[A](f: BSONCollection => Future[A]): Future[A]     = userColl flatMap f

  def simulExists(id: Simul.ID): Future[Boolean] = simulColl flatMap idExists(id)

  def teamExists(id: Simul.ID): Future[Boolean] = teamColl flatMap idExists(id)

  def swissExists(id: Simul.ID): Future[Boolean] = swissColl flatMap idExists(id)

  def tourExists(id: Simul.ID): Future[Boolean] = tourColl flatMap idExists(id)

  def studyExists(id: Study.ID): Future[Boolean] = studyColl flatMap idExists(id)

  def gameExists(id: Game.Id): Future[Boolean] =
    gameCache getIfPresent id match {
      case None        => gameColl flatMap idExists(id.value)
      case Some(entry) => entry.map(_.isDefined)(parasitic)
    }

  def player(fullId: Game.FullId, user: Option[User]): Future[Option[Game.RoundPlayer]] =
    gameCache
      .get(fullId.gameId)
      .map {
        _ flatMap {
          _.player(fullId.playerId, user.map(_.id))
        }
      }(parasitic)

  private val gameCacheProjection =
    BSONDocument("is" -> true, "us" -> true, "tid" -> true, "sid" -> true, "iid" -> true)

  private val gameCache: AsyncLoadingCache[Game.Id, Option[Game.Round]] = Scaffeine()
    .expireAfterWrite(10.minutes)
    .buildAsyncFuture { id =>
      gameColl flatMap {
        _.find(
          selector = BSONDocument("_id" -> id.value),
          projection = Some(gameCacheProjection)
        ).one[BSONDocument]
          .map { docOpt =>
            for {
              doc       <- docOpt
              playerIds <- doc.getAsOpt[String]("is")
              users = doc.getAsOpt[List[String]]("us") getOrElse Nil
              players = Color.Map(
                Game.Player(Game.PlayerId(playerIds take 4), users.headOption.filter(_.nonEmpty)),
                Game.Player(Game.PlayerId(playerIds drop 4), users lift 1)
              )
              ext =
                doc.getAsOpt[Tour.ID]("tid").map(Game.RoundExt.Tour.apply) orElse
                  doc.getAsOpt[Swiss.ID]("iid").map(Game.RoundExt.Swiss.apply) orElse
                  doc.getAsOpt[Simul.ID]("sid").map(Game.RoundExt.Simul.apply)
            } yield Game.Round(id, players, ext)
          }(parasitic)
      }
    }

  private val visibilityNotPrivate = BSONDocument("visibility" -> BSONDocument("$ne" -> "private"))

  def studyExistsFor(id: Simul.ID, user: Option[User]): Future[Boolean] =
    studyColl flatMap {
      exists(
        _,
        BSONDocument(
          "_id" -> id,
          user.fold(visibilityNotPrivate) { u =>
            BSONDocument(
              "$or" -> BSONArray(
                visibilityNotPrivate,
                BSONDocument(s"members.${u.id}" -> BSONDocument("$exists" -> true))
              )
            )
          }
        )
      )
    }

  def studyMembers(id: Study.ID): Future[Set[User.ID]] =
    studyColl flatMap {
      _.find(
        selector = BSONDocument("_id" -> id),
        projection = Some(BSONDocument("members" -> true))
      ).one[BSONDocument] map { docOpt =>
        for {
          doc     <- docOpt
          members <- doc.getAsOpt[BSONDocument]("members")
        } yield members.elements.map { case BSONElement(key, _) => key }.toSet
      } map (_ getOrElse Set.empty)
    }

  def tournamentActiveUsers(tourId: Tour.ID): Future[Set[User.ID]] =
    tourPlayerColl flatMap {
      _.distinct[User.ID, Set](
        key = "uid",
        selector = Some(BSONDocument("tid" -> tourId, "w" -> BSONDocument("$ne" -> true))),
        readConcern = ReadConcern.Local,
        collation = None
      )
    }

  def tournamentPlayingUsers(tourId: Tour.ID): Future[Set[User.ID]] =
    tourPairingColl flatMap {
      _.distinct[User.ID, Set](
        key = "u",
        selector = Some(BSONDocument("tid" -> tourId, "s" -> BSONDocument("$lt" -> chess.Status.Mate.id))),
        readConcern = ReadConcern.Local,
        collation = None
      )
    }

  def challenger(challengeId: Challenge.Id): Future[Option[Challenge.Challenger]] =
    challengeColl flatMap {
      _.find(
        selector = BSONDocument("_id" -> challengeId.value),
        projection = Some(BSONDocument("challenger" -> true))
      ).one[BSONDocument] map {
        _.flatMap {
          _.getAsOpt[BSONDocument]("challenger")
        } map { c =>
          val anon = c.getAsOpt[String]("s") map Challenge.Anon.apply
          val user = c.getAsOpt[String]("id") map Challenge.User.apply
          anon orElse user getOrElse Challenge.Open
        }
      }
    }

  private val userDataProjection =
    BSONDocument("username" -> true, "title" -> true, "plan" -> true, "_id" -> false)
  private def userDataReader(doc: BSONDocument) =
    for {
      name <- doc.getAsOpt[String]("username")
      title  = doc.getAsOpt[String]("title")
      patron = doc.child("plan").flatMap(_.getAsOpt[Boolean]("active")) getOrElse false
    } yield FriendList.UserData(name, title, patron)

  def loadFollowed(userId: User.ID): Future[Iterable[User.ID]] =
    relationColl flatMap {
      _.distinct[User.ID, List](
        key = "u2",
        selector = Some(BSONDocument("u1" -> userId, "r" -> true)),
        readConcern = ReadConcern.Local,
        collation = None
      )
    }

  def userData(userId: User.ID): Future[Option[FriendList.UserData]] =
    userColl flatMap {
      _.find(
        BSONDocument("_id" -> userId),
        Some(userDataProjection)
      ).one[BSONDocument](readPreference = ReadPreference.secondaryPreferred)
        .map { _ flatMap userDataReader }
    }

  object troll {

    def is(user: Option[User]): Future[IsTroll] =
      user.fold(Future successful IsTroll(false)) { u =>
        cache.get(u.id).map(IsTroll.apply)(parasitic)
      }

    def set(userId: User.ID, v: IsTroll): Unit =
      cache.put(userId, Future successful v.value)

    private val cache: AsyncLoadingCache[User.ID, Boolean] = Scaffeine()
      .expireAfterAccess(20.minutes)
      .buildAsyncFuture { id =>
        userColl flatMap { exists(_, BSONDocument("_id" -> id, "marks" -> "troll")) }
      }
  }

  object idFilter {
    import Mongo._
    val study: IdFilter = ids => studyColl flatMap filterIds(ids)
    val tour: IdFilter  = ids => tourColl flatMap filterIds(ids)
    val simul: IdFilter = ids => simulColl flatMap filterIds(ids)
    val team: IdFilter  = ids => teamColl flatMap filterIds(ids)
    val swiss: IdFilter = ids => swissColl flatMap filterIds(ids)
  }

  private def idExists(id: String)(coll: BSONCollection): Future[Boolean] =
    exists(coll, BSONDocument("_id" -> id))

  private def exists(coll: BSONCollection, selector: BSONDocument): Future[Boolean] =
    coll
      .count(
        selector = Some(selector),
        limit = None,
        skip = 0,
        hint = None,
        readConcern = ReadConcern.Local
      )
      .map(0 < _)(parasitic)

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
      bson.asTry[BSONDateTime] map { dt =>
        new DateTime(dt.value)
      }

    @inline def writeTry(date: DateTime) = Success(BSONDateTime(date.getMillis))
  }
}
