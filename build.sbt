name := "lila-ws"

version := "1.0-SNAPSHOT"

lazy val root = (project in file("."))

scalaVersion := "2.13.0"

val akkaVersion = "2.5.25"

val akkaHttpVersion = "10.1.9"

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-json" % "2.7.4",
  "org.reactivemongo" %% "reactivemongo" % "0.18.6",
  "io.lettuce" % "lettuce-core" % "5.1.8.RELEASE",
  "io.netty" % "netty-transport-native-epoll" % "4.1.39.Final" classifier "linux-x86_64",
  "org.lichess" %% "scalachess" % "9.0.25",
  "com.typesafe.akka" %% "akka-actor"           % akkaVersion,
  "com.typesafe.akka" %% "akka-actor-typed"     % akkaVersion,
  "com.typesafe.akka" %% "akka-stream"          % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j"           % akkaVersion,
  "com.typesafe.akka" %% "akka-http"            % akkaHttpVersion,
  "joda-time" % "joda-time" % "2.10.3",
  "com.typesafe" % "config" % "1.3.4",
  "ch.qos.logback" % "logback-classic" % "1.2.3"
)

resolvers += "lila-maven" at "https://raw.githubusercontent.com/ornicar/lila-maven/master"

scalacOptions ++= Seq(
  "-language:implicitConversions",
  "-feature",
  "-deprecation",
  "-Xfatal-warnings"
)

sources in (Compile, doc) := Seq.empty

publishArtifact in (Compile, packageDoc) := false

enablePlugins(JavaAppPackaging)
