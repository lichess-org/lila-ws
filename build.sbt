name := "lila-ws"

version := "1.0-SNAPSHOT"

maintainer := "lichess.org"

lazy val root = (project in file("."))
.enablePlugins(PlayScala, PlayNettyServer)
.disablePlugins(PlayFilters, PlayAkkaHttpServer)
.enablePlugins(JavaAppPackaging, JavaAgent)

val akkaVersion = "2.5.26"
val kamonVersion= "2.0.1"
val reactivemongoVersion = "0.19.0"

scalaVersion := "2.13.1"

libraryDependencies += guice
libraryDependencies += "org.reactivemongo" %% "reactivemongo" % reactivemongoVersion
libraryDependencies += "org.reactivemongo" %% "reactivemongo-bson-api" % reactivemongoVersion
libraryDependencies += "org.reactivemongo" % "reactivemongo-shaded-native" % s"$reactivemongoVersion-linux-x86-64" % "runtime"
libraryDependencies += "io.lettuce" % "lettuce-core" % "5.2.1.RELEASE"
libraryDependencies += "io.netty" % "netty-transport-native-epoll" % "4.1.43.Final" classifier "linux-x86_64"
libraryDependencies += "org.lichess" %% "scalachess" % "9.0.26"
libraryDependencies += "com.typesafe.akka" %% "akka-actor" % akkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-slf4j" % akkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-protobuf" % akkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-stream" % akkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion
libraryDependencies += "joda-time" % "joda-time" % "2.10.5"
libraryDependencies += "com.github.blemale" %% "scaffeine" % "3.1.0" % "compile"
libraryDependencies += "io.kamon" %% "kamon-core" % kamonVersion
libraryDependencies += "io.kamon" %% "kamon-influxdb" % "2.0.0"
libraryDependencies += "io.kamon" %% "kamon-system-metrics" % "2.0.0"
libraryDependencies += "io.kamon" %% "kamon-akka" % "2.0.0"

resolvers += "lila-maven" at "https://raw.githubusercontent.com/ornicar/lila-maven/master"

scalacOptions ++= Seq(
  "-language:implicitConversions",
  "-feature",
  "-deprecation",
  "-Xfatal-warnings"
)

javaAgents += "io.kamon" % "kanela-agent" % "1.0.3"

sources in (Compile, doc) := Seq.empty

publishArtifact in (Compile, packageDoc) := false
