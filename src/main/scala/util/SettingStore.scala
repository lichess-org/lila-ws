package lila.ws
package util

import reactivemongo.api.bson.*

/* A setting that is read from the database every `ttl`.
 *
 * Usage:
 * val nbDogs: Setting[Int] = settingStore.makeSetting("dogs", 48, 1.minute)
 * println("dogs: " + nbDogs.get)
 *
 * Note that the first reads are not guaranteed to be from the DB,
 * but could return the hardcoded default value instead.
 *
 * To change the value of a setting, you need to update the DB directly, using mongosh:
 * db.setting.updateOne({_id:'dogs'},{$set:{value:50}})
 */

final class Setting[A](default: A, ttl: FiniteDuration)(fetch: () => Future[Option[A]])(using
    ec: Executor,
    scheduler: Scheduler
):
  private var value: A = default

  def get(): A = value

  private def readFromDb(): Unit =
    fetch().foreach: opt =>
      value = opt | default

  scheduler.scheduleWithFixedDelay(1.millis, ttl)(() => readFromDb())

final class SettingStore(mongo: Mongo)(using Executor, Scheduler):

  def makeSetting[A: BSONReader](key: String, default: A, ttl: FiniteDuration = 20.seconds): Setting[A] =
    Setting[A](default, ttl): () =>
      mongo.settingColl.flatMap:
        _.find(selector = BSONDocument("_id" -> s"lila-ws.$key"))
          .one[BSONDocument]
          .map(_.flatMap(_.getAsOpt[A]("value")))
