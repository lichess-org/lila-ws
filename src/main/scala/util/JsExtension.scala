package lila.ws

import play.api.libs.json.*

given [A, T](using
    bts: SameRuntime[A, T],
    stb: SameRuntime[T, A],
    format: Format[A]
): Format[T] = format.bimap(bts.apply, stb.apply)

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

  def arr(key: String): Option[JsArray] =
    (js \ key).asOpt[JsArray]

  def arrAs[A](key: String)(as: JsValue => Option[A]): Option[List[A]] =
    arr(key) map: j =>
      j.value.iterator.map(as).to(List).flatten

  def objs(key: String): Option[List[JsObject]] = arrAs(key)(_.asOpt[JsObject])

  def add(pair: (String, Boolean)): JsObject =
    if pair._2 then js + (pair._1 -> JsBoolean(true))
    else js

  def add[A: Writes](pair: (String, Option[A])): JsObject =
    pair._2.fold(js) { a => js + (pair._1 -> Json.toJson(a)) }
