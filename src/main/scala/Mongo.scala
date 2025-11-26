package lila.ws

import chess.ByColor
import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import com.typesafe.config.Config
import reactivemongo.api.bson.*
import reactivemongo.api.bson.collection.BSONCollection
import reactivemongo.api.commands.WriteResult
import reactivemongo.api.{ AsyncDriver, DB, MongoConnection, ReadConcern, ReadPreference, WriteConcern }

import java.time.LocalDateTime
import scala.util.{ Success, Try }

final class Mongo(config: Config)(using Executor)(using cacheApi: util.CacheApi) extends MongoHandlers:

  private val driver = new AsyncDriver(Some(config.getConfig("reactivemongo")))

  private type Coll = reactivemongo.api.bson.collection.BSONCollection

  private val mainConnection =
    MongoConnection.fromString(config.getString("mongo.uri").pp("main")).flatMap { parsedUri =>
      driver.connect(parsedUri).map(_ -> parsedUri.db)
    }
  private def mainDb: Future[DB] =
    mainConnection.flatMap: (conn, dbName) =>
      conn.database(dbName.getOrElse("lichess"))

  private val studyConnection =
    MongoConnection.fromString(config.getString("study.mongo.uri").pp("study")).flatMap { parsedUri =>
      driver.connect(parsedUri).map(_ -> parsedUri.db)
    }
  private def studyDb: Future[DB] =
    studyConnection.flatMap: (conn, dbName) =>
      conn.database(dbName.getOrElse("lichess"))

  private val yoloConnection =
    MongoConnection.fromString(config.getString("yolo.mongo.uri").pp("yolo")).flatMap { parsedUri =>
      driver.connect(parsedUri).map(_ -> parsedUri.db)
    }
  private def yoloDb: Future[DB] =
    yoloConnection.flatMap: (conn, dbName) =>
      conn.database(dbName.getOrElse("lichess"))

  private def collNamed(name: String): Future[Coll] = mainDb.map(_.collection(name))(using parasitic)
  def securityColl = collNamed("security")
  def userColl = collNamed("user4")
  def coachColl = collNamed("coach")
  def streamerColl = collNamed("streamer")
  def simulColl = collNamed("simul")
  def tourColl = collNamed("tournament2")
  def tourPlayerColl = collNamed("tournament_player")
  def tourPairingColl = collNamed("tournament_pairing")
  def gameColl = collNamed("game5")
  def challengeColl = collNamed("challenge")
  def relationColl = collNamed("relation")
  def teamColl = collNamed("team")
  def teamMemberColl = collNamed("team_member")
  def swissColl = collNamed("swiss")
  def reportColl = collNamed("report2")
  def oauthColl = collNamed("oauth2_access_token")
  def relayTourColl = collNamed("relay_tour")
  def relayRoundColl = collNamed("relay")
  def settingColl = collNamed("setting")
  def cacheColl = collNamed("cache")
  def studyColl = studyDb.map(_.collection("study"))(using parasitic)
  def evalCacheColl = yoloDb.map(_.collection("eval_cache2"))(using parasitic)

  def isDuplicateKey(wr: WriteResult) = wr.code.contains(11000)
  def ignoreDuplicateKey: PartialFunction[Throwable, Unit] =
    case wr: WriteResult if isDuplicateKey(wr) => ()

  def security[A](f: BSONCollection => Future[A]): Future[A] = securityColl.flatMap(f)
  def coach[A](f: BSONCollection => Future[A]): Future[A] = coachColl.flatMap(f)
  def streamer[A](f: BSONCollection => Future[A]): Future[A] = streamerColl.flatMap(f)
  def user[A](f: BSONCollection => Future[A]): Future[A] = userColl.flatMap(f)

  def simulExists(id: Simul.Id): Future[Boolean] = simulColl.flatMap(idExists(id.value))

  private def isTeamMember(teamId: Team.Id, user: User.Id): Future[Boolean] =
    teamMemberColl.flatMap { exists(_, BSONDocument("_id" -> s"${user.value}@$teamId")) }

  def teamView(id: Team.Id, me: Option[User.Id]): Future[Option[Team.HasChat]] =
    teamColl
      .flatMap:
        _.find(
          selector = BSONDocument("_id" -> id),
          projection = Some(BSONDocument("chat" -> true, "leaders" -> true))
        ).one[BSONDocument]
      .zip:
        me.fold(Future.successful(false)) { isTeamMember(id, _) }
      .map:
        case (None, _) => None
        case (_, false) => Some(Team.HasChat(false))
        case (Some(teamDoc), true) =>
          Some:
            Team.HasChat:
              teamDoc
                .int("chat")
                .exists: chat =>
                  chat == Team.Access.Members.id ||
                    (chat == Team.Access.Leaders.id && me.fold(false): me =>
                      teamDoc.getAsOpt[Set[User.Id]]("leaders").exists(_ contains me))

  def swissExists(id: Swiss.Id): Future[Boolean] = swissColl.flatMap(idExists(id))

  object tourExists:
    private val cache: AsyncLoadingCache[Tour.Id, Boolean] = cacheApi(32, "tour.exists"):
      _.expireAfterWrite(2.seconds).buildAsyncFuture(id => tourColl.flatMap(idExists(id)))
    def apply(id: Tour.Id): Future[Boolean] = cache.get(id)

  def studyExists(id: Study.Id): Future[Boolean] = studyColl.flatMap(idExists(id))

  // None = no such game
  def gameUserIds(id: Game.Id): Future[Option[List[User.Id]]] =
    gameCache.get(id).map(_.map(_.players.mapList(_.userId).flatten))

  def player(fullId: Game.FullId, user: Option[User.Id]): Future[Option[Game.RoundPlayer]] =
    gameCache
      .get(fullId.gameId)
      .map {
        _.flatMap:
          _.player(fullId.playerId, user)
      }(using parasitic)

  private val gameCacheProjection =
    BSONDocument("is" -> true, "us" -> true, "tid" -> true, "sid" -> true, "iid" -> true)

  private val gameCache: AsyncLoadingCache[Game.Id, Option[Game.Round]] = cacheApi(65_536, "game.round"):
    _.expireAfterWrite(10.minutes).buildAsyncFuture: id =>
      gameColl.flatMap:
        _.find(
          selector = BSONDocument("_id" -> id.value),
          projection = Some(gameCacheProjection)
        ).one[BSONDocument]
          .map { docOpt =>
            for
              doc <- docOpt
              playerIds <- doc.getAsOpt[String]("is")
              users = doc.getAsOpt[List[User.Id]]("us").getOrElse(Nil)
              players = ByColor(
                Game.Player(Game.PlayerId(playerIds.take(4)), users.headOption.filter(_.value.nonEmpty)),
                Game.Player(Game.PlayerId(playerIds.drop(4)), users.lift(1))
              )
              ext =
                doc
                  .getAsOpt[Tour.Id]("tid")
                  .map(Game.RoundExt.InTour.apply)
                  .orElse(doc.getAsOpt[Swiss.Id]("iid").map(Game.RoundExt.InSwiss.apply))
                  .orElse(doc.getAsOpt[Simul.Id]("sid").map(Game.RoundExt.InSimul.apply))
            yield Game.Round(id, players, ext)
          }(using parasitic)

  private val visibilityNotPrivate = BSONDocument("visibility" -> BSONDocument("$ne" -> "private"))

  def isGameOngoing(id: Game.Id): Future[Boolean] =
    gameColl.flatMap:
      exists(_, BSONDocument("_id" -> id, "s" -> BSONDocument("$lt" -> chess.Status.Aborted.id)))

  def studyExistsFor(id: Study.Id, user: Option[User.Id]): Future[Boolean] =
    studyColl.flatMap:
      exists(
        _,
        BSONDocument(
          "_id" -> id,
          user.fold(visibilityNotPrivate): u =>
            BSONDocument(
              "$or" -> BSONArray(
                visibilityNotPrivate,
                BSONDocument(s"members.${u.value}" -> BSONDocument("$exists" -> true))
              )
            )
        )
      )

  def studyMembers(id: Study.Id): Future[Set[User.Id]] =
    studyColl.flatMap:
      _.find(
        selector = BSONDocument("_id" -> id),
        projection = Some(BSONDocument("members" -> true))
      ).one[BSONDocument]
        .map { docOpt =>
          for
            doc <- docOpt
            members <- doc.getAsOpt[BSONDocument]("members")
          yield members.elements.collect { case BSONElement(key, _) => User.Id(key) }.toSet
        }
        .map(_.getOrElse(Set.empty))

  import evalCache.{ Id, EvalCacheEntry }
  def evalCacheEntry(id: Id): Future[Option[EvalCacheEntry]] =
    import evalCache.EvalCacheBsonHandlers.given
    evalCacheColl.flatMap:
      _.find(selector = BSONDocument("_id" -> id))
        .one[EvalCacheEntry]
  def evalCacheUsedNow(id: Id): Unit =
    import evalCache.EvalCacheBsonHandlers.given
    evalCacheColl.foreach:
      _.update(ordered = false, writeConcern = WriteConcern.Unacknowledged)
        .one(BSONDocument("_id" -> id), BSONDocument("$set" -> BSONDocument("usedAt" -> LocalDateTime.now)))

  def tournamentActiveUsers(tourId: Tour.Id): Future[Set[User.Id]] =
    tourPlayerColl.flatMap:
      _.distinct[User.Id, Set](
        key = "uid",
        selector = Some(BSONDocument("tid" -> tourId, "w" -> BSONDocument("$ne" -> true))),
        readConcern = ReadConcern.Local,
        collation = None
      )

  def tournamentPlayingUsers(tourId: Tour.Id): Future[Set[User.Id]] =
    tourPairingColl.flatMap:
      _.distinct[User.Id, Set](
        key = "u",
        selector = Some(BSONDocument("tid" -> tourId, "s" -> BSONDocument("$lt" -> chess.Status.Mate.id))),
        readConcern = ReadConcern.Local,
        collation = None
      )

  def challenger(challengeId: Challenge.Id): Future[Option[Challenge.Challenger]] =
    challengeColl.flatMap:
      _.find(
        selector = BSONDocument("_id" -> challengeId.value),
        projection = Some(BSONDocument("challenger" -> true))
      ).one[BSONDocument]
        .map:
          _.flatMap {
            _.getAsOpt[BSONDocument]("challenger")
          }.map { c =>
            val anon = c.getAsOpt[String]("s").map(Challenge.Challenger.Anon.apply)
            val user = c.getAsOpt[User.Id]("id").map(Challenge.Challenger.User.apply)
            anon.orElse(user).getOrElse(Challenge.Challenger.Open)
          }

  def inquirers: Future[List[User.Id]] =
    reportColl.flatMap:
      _.distinct[User.Id, List](
        key = "inquiry.mod",
        selector = Some(BSONDocument("inquiry.mod" -> BSONDocument("$exists" -> true))),
        readConcern = ReadConcern.Local,
        collation = None
      )

  private val userDataProjection =
    BSONDocument("username" -> true, "title" -> true, "plan" -> true, "_id" -> false)
  private def userDataReader(doc: BSONDocument) =
    val patronColor = for
      plan <- doc.child("plan")
      if plan.getAsOpt[Boolean]("active") | false
      months = plan.getAsOpt[Int]("months") | 0
      lifetime = plan.getAsOpt[Boolean]("lifetime") | false
      manualColor = plan.getAsOpt[User.patron.PatronColor]("color")
    yield manualColor | User.patron.autoColor(months, lifetime)
    for
      name <- doc.getAsOpt[User.Name]("username")
      title = doc.getAsOpt[User.Title]("title")
      patron = patronColor
    yield FriendList.UserData(name, title, patron)

  def loadFollowed(userId: User.Id): Future[Iterable[User.Id]] =
    relationColl.flatMap:
      _.distinct[User.Id, List](
        key = "u2",
        selector = Some(BSONDocument("u1" -> userId, "r" -> true)),
        readConcern = ReadConcern.Local,
        collation = None
      )

  def isBlockedByAny(id: Game.Id, by: Iterable[User.Id])(me: User.Id): Future[Boolean] =
    if by.isEmpty then Future.successful(false)
    else
      relationColl
        .flatMap:
          exists(_, BSONDocument("_id" -> BSONDocument("$in" -> by.map(b => s"$b/$me")), "r" -> false))
        .flatMap:
          if _ then isGameOngoing(id) else Future.successful(false)

  def userData(userId: User.Id): Future[Option[FriendList.UserData]] =
    userColl.flatMap:
      _.find(
        BSONDocument("_id" -> userId),
        Some(userDataProjection)
      ).one[BSONDocument](readPreference = ReadPreference.secondaryPreferred)
        .map { _.flatMap(userDataReader) }

  object troll:

    def is(user: Option[User.Id]): Future[IsTroll] =
      user.fold(Future.successful(IsTroll(false)))(cache.get)

    def set(userId: User.Id, v: IsTroll): Unit =
      cache.put(userId, Future.successful(v))

    private val cache: AsyncLoadingCache[User.Id, IsTroll] = cacheApi(65_536, "user.troll"):
      _.expireAfterAccess(20.minutes).buildAsyncFuture: id =>
        userColl.flatMap:
          exists(_, BSONDocument("_id" -> id, "marks" -> "troll")).map(IsTroll.apply(_))

  object idFilter:
    val study: IdFilter = ids => studyColl.flatMap(filterIds(ids))
    val tour: IdFilter = ids => tourColl.flatMap(filterIds(ids))
    val simul: IdFilter = ids => simulColl.flatMap(filterIds(ids))
    val team: IdFilter = ids => teamColl.flatMap(filterIds(ids))
    val swiss: IdFilter = ids => swissColl.flatMap(filterIds(ids))

  object cache:
    def get[A: BSONReader](key: String): Future[Option[A]] = for
      coll <- cacheColl
      res <- coll
        .find(BSONDocument("_id" -> key))
        .one[BSONDocument](readPreference = ReadPreference.secondaryPreferred)
    yield
      for
        doc <- res
        v <- doc.getAsOpt[A]("v")
      yield v

  private def idExists[Id: BSONWriter](id: Id)(coll: BSONCollection): Future[Boolean] =
    exists(coll, BSONDocument("_id" -> id))

  private def exists(coll: BSONCollection, selector: BSONDocument): Future[Boolean] =
    coll
      .count(
        selector = Some(selector),
        limit = Some(1),
        skip = 0,
        hint = None,
        readConcern = ReadConcern.Local
      )
      .map(0 < _)(using parasitic)

  private def filterIds(ids: Iterable[String])(coll: BSONCollection): Future[Set[String]] =
    coll.distinct[String, Set](
      key = "_id",
      selector = Some(BSONDocument("_id" -> BSONDocument("$in" -> ids))),
      readConcern = ReadConcern.Local,
      collation = None
    )

trait MongoHandlers:

  type IdFilter = Iterable[String] => Future[Set[String]]

  given localDateHandler: BSONHandler[LocalDateTime] with
    def readTry(bson: BSONValue): Try[LocalDateTime] =
      bson.asTry[BSONDateTime].map { dt =>
        millisToDateTime(dt.value)
      }
    def writeTry(date: LocalDateTime) = Success(BSONDateTime(date.toMillis))

  given [A, T](using
      bts: SameRuntime[A, T],
      stb: SameRuntime[T, A],
      handler: BSONHandler[A]
  ): BSONHandler[T] = handler.as(bts.apply, stb.apply)

object Mongo extends MongoHandlers
