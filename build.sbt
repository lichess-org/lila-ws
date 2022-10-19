name := "lila-ws"

version := "3.0"

lazy val `lila-ws` = (project in file("."))
  .enablePlugins(JavaAppPackaging)

val akkaVersion          = "2.6.20"
val kamonVersion         = "2.5.9"
val nettyVersion         = "4.1.84.Final"
val reactivemongoVersion = "1.1.0-RC4"

val os = sys.props.get("os.name") match {
  case Some(osName) if osName.toLowerCase.startsWith("mac") => "osx"
  case _                                                    => "linux"
}

scalaVersion := "3.1.3"

libraryDependencies += "org.reactivemongo" %% "reactivemongo"          % reactivemongoVersion
libraryDependencies += "org.reactivemongo" %% "reactivemongo-bson-api" % reactivemongoVersion
libraryDependencies += "org.reactivemongo" % "reactivemongo-shaded-native" % s"$reactivemongoVersion-$os-x86-64"
libraryDependencies += "io.lettuce" % "lettuce-core"                 % "6.2.1.RELEASE"
libraryDependencies += "io.netty"   % "netty-handler"                % nettyVersion
libraryDependencies += "io.netty"   % "netty-codec-http"             % nettyVersion
libraryDependencies += "io.netty"   % "netty-transport-native-epoll" % nettyVersion classifier "linux-x86_64"
libraryDependencies += "com.github.ornicar" %% "scalalib"         % "8.0.2"
libraryDependencies += "org.lichess"        %% "scalachess"       % "11.1.0"
libraryDependencies += "com.typesafe.akka"  %% "akka-actor-typed" % akkaVersion
// libraryDependencies += "com.typesafe.akka"          %% "akka-slf4j"       % akkaVersion
libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.5"
libraryDependencies += "joda-time"                   % "joda-time"       % "2.12.0"
libraryDependencies += "com.github.blemale"         %% "scaffeine"       % "5.2.1" % "compile"
libraryDependencies += "ch.qos.logback"              % "logback-classic" % "1.4.4"
libraryDependencies += "com.typesafe.play"          %% "play-json"       % "2.10.0-RC6"
libraryDependencies += "io.kamon"                   %% "kamon-core"      % kamonVersion
libraryDependencies += "io.kamon"                   %% "kamon-influxdb"  % kamonVersion
// libraryDependencies += "io.kamon"                   %% "kamon-system-metrics"         % kamonVersion
libraryDependencies += "com.softwaremill.macwire" %% "macros" % "2.5.8" % "provided"
libraryDependencies += "com.roundeights"          %% "hasher" % "1.3.0"

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
  "-language:postfixOps"
  // Warnings as errors!
  // "-Xfatal-warnings",
)

javaOptions ++= Seq("-Xms32m", "-Xmx256m")

Compile / doc / sources := Seq.empty

Compile / packageDoc / publishArtifact := false

/* scalafmtOnCompile := true */
