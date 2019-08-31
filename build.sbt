name := "lila-ws"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala).disablePlugins(PlayFilters)

/* val akkaVersion = "2.6.0-M2" */

scalaVersion := "2.13.0"

libraryDependencies += guice
libraryDependencies += "org.reactivemongo" %% "reactivemongo" % "0.18.4"
libraryDependencies += "io.lettuce" % "lettuce-core" % "5.1.8.RELEASE"
libraryDependencies += "com.github.blemale" %% "scaffeine" % "3.1.0" % "compile"
libraryDependencies += "com.github.blemale" %% "scaffeine" % "3.1.0" % "compile"
libraryDependencies += "org.lichess" %% "scalachess" % "9.0.0"

resolvers += "lila-maven" at "https://raw.githubusercontent.com/ornicar/lila-maven/master"

scalacOptions ++= Seq(
  "-language:implicitConversions",
  "-feature",
  "-deprecation",
  "-Xfatal-warnings"
)
