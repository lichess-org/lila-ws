package lila.ws.util

import play.api.libs.json.*

object JsExtension:

  extension (js: JsObject)

    def str(key: String): Option[String] =
      (js \ key).asOpt[String]

    def int(key: String): Option[Int] =
      (js \ key).asOpt[Int]

    def boolean(key: String): Option[Boolean] =
      (js \ key).asOpt[Boolean]

    def obj(key: String): Option[JsObject] =
      (js \ key).asOpt[JsObject]

    def get[A: Reads](key: String): Option[A] =
      (js \ key).asOpt[A]

    def add(pair: (String, Boolean)): JsObject =
      if (pair._2) js + (pair._1 -> JsBoolean(true))
      else js

    def add[A: Writes](pair: (String, Option[A])): JsObject =
      pair._2.fold(js) { a => js + (pair._1 -> Json.toJson(a)) }
