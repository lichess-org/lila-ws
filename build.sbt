name := "lila-ws"

version := "1.0-SNAPSHOT"

maintainer := "lichess.org"

lazy val root = (project in file("."))
.enablePlugins(PlayScala, PlayAkkaHttpServer)
.disablePlugins(PlayFilters, PlayNettyServer)

/* val akkaVersion = "2.6.0-M2" */

scalaVersion := "2.13.1"

val akkaVersion = "2.5.23"

libraryDependencies += guice
libraryDependencies += "org.reactivemongo" %% "reactivemongo" % "0.18.7"
libraryDependencies += "io.lettuce" % "lettuce-core" % "5.2.0.RELEASE"
libraryDependencies += "io.netty" % "netty-transport-native-epoll" % "4.1.39.Final" classifier "linux-x86_64"
libraryDependencies += "org.lichess" %% "scalachess" % "9.0.25"
libraryDependencies += "com.typesafe.akka" %% "akka-actor" % akkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion
libraryDependencies += "joda-time" % "joda-time" % "2.10.4"
libraryDependencies += "com.github.blemale" %% "scaffeine" % "3.1.0" % "compile"

resolvers += "lila-maven" at "https://raw.githubusercontent.com/ornicar/lila-maven/master"

scalacOptions ++= Seq(
  "-language:implicitConversions",
  "-feature",
  "-deprecation",
  "-Xfatal-warnings"
)

sources in (Compile, doc) := Seq.empty

publishArtifact in (Compile, packageDoc) := false
