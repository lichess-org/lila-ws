name := "lila-ws"

version := "2.0"

maintainer := "lichess.org"

lazy val root = (project in file("."))
.enablePlugins(JavaAppPackaging)

val akkaVersion = "2.6.0"
val kamonVersion= "2.0.1"
val reactivemongoVersion = "0.19.1"

scalaVersion := "2.13.1"

libraryDependencies += "org.reactivemongo" %% "reactivemongo" % reactivemongoVersion
libraryDependencies += "org.reactivemongo" %% "reactivemongo-bson-api" % reactivemongoVersion
libraryDependencies += "org.reactivemongo" % "reactivemongo-shaded-native" % s"$reactivemongoVersion-linux-x86-64" % "runtime"
libraryDependencies += "io.lettuce" % "lettuce-core" % "5.2.1.RELEASE"
libraryDependencies += "com.google.inject" % "guice" % "4.2.2"
libraryDependencies += "io.netty" % "netty-all" % "4.1.43.Final"
libraryDependencies += "io.netty" % "netty-transport-native-epoll" % "4.1.43.Final" classifier "linux-x86_64"
libraryDependencies += "org.lichess" %% "scalachess" % "9.0.26"
libraryDependencies += "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion
libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"
libraryDependencies += "joda-time" % "joda-time" % "2.10.5"
libraryDependencies += "com.github.blemale" %% "scaffeine" % "3.1.0" % "compile"
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3"
libraryDependencies += "com.typesafe.play" %% "play-json" % "2.8.0"
libraryDependencies += "io.kamon" %% "kamon-core" % kamonVersion
libraryDependencies += "io.kamon" %% "kamon-influxdb" % "2.0.0"
libraryDependencies += "io.kamon" %% "kamon-system-metrics" % "2.0.0"

resolvers += "lila-maven" at "https://raw.githubusercontent.com/ornicar/lila-maven/master"

scalacOptions ++= Seq(
  "-language:implicitConversions",
  "-feature",
  "-deprecation",
  "-Xfatal-warnings"
)

sources in (Compile, doc) := Seq.empty

publishArtifact in (Compile, packageDoc) := false
