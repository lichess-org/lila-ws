name := "lila-ws"

version := "3.1"

lazy val `lila-ws` = (project in file("."))
  .enablePlugins(JavaAppPackaging)

resolvers += ("snapshots" at "https://oss.sonatype.org/content/repositories/snapshots")

val akkaVersion  = "2.6.20"
val kamonVersion = "2.6.1"
val nettyVersion = "4.1.94.Final"

val os = sys.props.get("os.name") match {
  case Some(osName) if osName.toLowerCase.startsWith("mac") => "osx"
  case _                                                    => "linux"
}
val shaded = !System.getProperty("os.arch").toLowerCase.startsWith("aarch")

scalaVersion := "3.3.0"

libraryDependencies += "org.reactivemongo" %% "reactivemongo" % "1.1.0-RC9"
libraryDependencies ++= (
  if (shaded) List("org.reactivemongo" % "reactivemongo-shaded-native" % s"1.1.0-RC6-$os-x86-64")
  else Nil
)
libraryDependencies += "io.lettuce" % "lettuce-core"     % "6.2.4.RELEASE"
libraryDependencies += "io.netty"   % "netty-handler"    % nettyVersion
libraryDependencies += "io.netty"   % "netty-codec-http" % nettyVersion
libraryDependencies += "io.netty" % s"netty-transport-native-epoll" % nettyVersion classifier s"linux-x86_64" classifier s"linux-aarch_64"
libraryDependencies += "io.netty" % s"netty-transport-native-kqueue" % nettyVersion classifier s"osx-x86_64" classifier s"osx-aarch_64"
libraryDependencies += "com.github.ornicar" %% "scalalib"         % "9.5.4"
libraryDependencies += "org.lichess"        %% "scalachess"       % "15.3.2"
libraryDependencies += "com.typesafe.akka"  %% "akka-actor-typed" % akkaVersion
// libraryDependencies += "com.typesafe.akka"          %% "akka-slf4j"       % akkaVersion
libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.5"
libraryDependencies += "com.github.blemale"         %% "scaffeine"       % "5.2.1" % "compile"
libraryDependencies += "ch.qos.logback"              % "logback-classic" % "1.4.8"
libraryDependencies += "com.typesafe.play"          %% "play-json"       % "2.10.0-RC9"
libraryDependencies += "io.kamon"                   %% "kamon-core"      % kamonVersion
libraryDependencies += "io.kamon"                   %% "kamon-influxdb"  % kamonVersion
// libraryDependencies += "io.kamon"                   %% "kamon-system-metrics"         % kamonVersion
libraryDependencies += "com.softwaremill.macwire" %% "macros" % "2.5.8" % "provided"
libraryDependencies += "com.roundeights"          %% "hasher" % "1.3.1"

resolvers ++= Resolver.sonatypeOssRepos("snapshots")
resolvers += "lila-maven" at "https://raw.githubusercontent.com/ornicar/lila-maven/master"

scalacOptions := Seq(
  "-encoding",
  "utf-8",
  "-rewrite",
  "-source:future-migration",
  "-indent",
  "-explaintypes",
  "-feature",
  "-language:postfixOps",
  "-Xtarget:12",
  "-Wunused:all"
  /* "-Wunused:nowarn" */

  // Warnings as errors!
  // "-Xfatal-warnings",
)

javaOptions ++= Seq("-Xms32m", "-Xmx256m")

Compile / doc / sources := Seq.empty

Compile / packageDoc / publishArtifact := false

/* scalafmtOnCompile := true */
