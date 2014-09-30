import sbt._
import sbt.Keys._
import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

//object BuildSettings {
//  val buildOrganization = "goldratio"
//  val buildVersion      = "0.1.0"
//  val buildScalaVersion = "2.10.3"
//
//  val buildSettings = sbt.Defaults.defaultSettings ++ Seq (
//    organization := buildOrganization,
//    version      := buildVersion,
//    scalaVersion := buildScalaVersion
//  )
//
//  scalacOptions ++= Seq(
//    "-unchecked",
//    "-deprecation",
//    "-feature",
//    "-encoding", "utf8"
//  )
//}

object Resolvers {
  val sprayRepo       = "spray"                  at "http://repo.spray.io/"
  val sprayNightlies  = "Spray Nightlies"        at "http://nightlies.spray.io/"
  val sonatypeRel     = "Sonatype OSS Releases"  at "http://oss.sonatype.org/content/repositories/releases/"
  val sonatypeSnap    = "Sonatype OSS Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"

  val sprayResolvers    = Seq(sprayRepo, sprayNightlies)
  val sonatypeResolvers = Seq(sonatypeRel, sonatypeSnap)
  val allResolvers      = sprayResolvers ++ sonatypeResolvers
}

object Dependencies {
  val SPRAY_VERSION = "1.3.2-20140428"
  val AKKA_VERSION = "2.3.5"

  val spray_websocket = "com.wandoulabs.akka" %% "spray-websocket" % "0.1.2"
  val spray_can = "io.spray" % "spray-can" % SPRAY_VERSION
  val spray_client = "io.spray" % "spray-client" % SPRAY_VERSION
  val akka_actor = "com.typesafe.akka" %% "akka-actor" % AKKA_VERSION
  val akka_contrib = "com.typesafe.akka" %% "akka-contrib" % AKKA_VERSION
  val akka_testkit = "com.typesafe.akka" %% "akka-testkit" % AKKA_VERSION % "test"
  val akka_multinode_testkit = "com.typesafe.akka" %% "akka-multi-node-testkit" % AKKA_VERSION % "test"
  val scalatest = "org.scalatest" %% "scalatest" % "2.0" % "test"
  val rxscala = "com.netflix.rxjava" % "rxjava-scala" % "0.17.1"
  val apache_math = "org.apache.commons" % "commons-math3" % "3.2"
  val parboiled = "org.parboiled" %% "parboiled-scala" % "1.1.5"
  val parboiled2 = "org.parboiled" %% "parboiled" % "2.0-M2"
  val caliper = "com.google.caliper" % "caliper" % "1.0-beta-1" % "test"
  val reactivemongo = "org.reactivemongo" %% "reactivemongo" % "0.10.5.akka23-SNAPSHOT"
  val playFunctional = "com.typesafe.play" %% "play-iteratees" % "2.3.0"
  val playIteratee = "com.typesafe.play" %% "play-functional" % "2.3.0"
  val playJson = "com.typesafe.play" %% "play-json" % "2.3.0"
  val jodatime = "joda-time" % "joda-time" % "2.3"
  val jodaConvert = "org.joda" % "joda-convert" % "1.6"
  val akkaApns = "com.github.mqshen" %% "akka-apns" % "0.1.0-SNAPSHOT"

  val jacksons = Seq(
    "jackson-core",
    "jackson-annotations",
    "jackson-databind"
  ).map("com.fasterxml.jackson.core" % _ % "2.3.2")

//  val all = Seq( spray_can, spray_caching, spray_json, akka_actor, akka_contrib, parboiled,
//    rxscala, akka_testkit, akka_multinode_testkit, scalatest, apache_math, caliper, reactivemongo,
//    playIteratee, playJson, jodatime, jodaConvert
//    //, spray_websocket
//  ) ++ jacksons

val all = Seq(
  spray_websocket,
  spray_can,
  rxscala,
  akka_actor,
  akka_contrib,
  playJson,
  jodaConvert,
  parboiled,
  reactivemongo,
  scalatest,
  akka_testkit,
  akka_multinode_testkit,
  apache_math,
  spray_client,
  caliper,
  akkaApns
//  jodatime,
// akka_multinode_testkit,
//  //apache_math,
//  //socketio,
//  , playIteratee,
) ++ jacksons
}

object Build extends sbt.Build {

  lazy val basicSettings = Seq(
    organization := "goldratio",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "2.10.4",
    scalacOptions ++= Seq("-unchecked", "-deprecation"),
    resolvers ++= Seq(
      "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases",
      "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
      "Typesafe repo" at "http://repo.typesafe.com/typesafe/releases/",
      "spray" at "http://repo.spray.io",
      "spray nightly" at "http://nightlies.spray.io/",
      "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven")
  )

  lazy val mainProject = Project( "Campfile", file("."))
    .settings(basicSettings: _*)
    .settings(formatSettings: _*)
    .settings(releaseSettings: _*)
    .settings(SbtMultiJvm.multiJvmSettings ++ multiJvmSettings: _*)
    .settings(libraryDependencies ++= Dependencies.all)
    .settings(unmanagedSourceDirectories in Test += baseDirectory.value / "multi-jvm/scala")
    .settings(XitrumPackage.skip: _*)
    .configs(MultiJvm)

  lazy val releaseSettings = Seq(
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (version.value.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases"  at nexus + "service/local/staging/deploy/maven2")
    },
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := { (repo: MavenRepository) => false },
    pomExtra := (
      <url>https://github.com/mqshen/spray-socketio</url>
        <licenses>
          <license>
            <name>The Apache Software License, Version 2.0</name>
            <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
            <distribution>repo</distribution>
          </license>
        </licenses>
        <developers>
          <developer>
            <id>shenmq</id>
            <name>miaoqi shen</name>
            <email>goldratio87@gmail.com</email>
          </developer>
        </developers>
      )
  )

  def multiJvmSettings = Seq(
    // make sure that MultiJvm test are compiled by the default test compilation
    compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
    // disable parallel tests
    parallelExecution in Test := false,
    // make sure that MultiJvm tests are executed by the default test target,
    // and combine the results from ordinary test and multi-jvm tests
    executeTests in Test <<= (executeTests in Test, executeTests in MultiJvm) map {
      case (testResults, multiNodeResults) =>
        val overall =
          if (testResults.overall.id < multiNodeResults.overall.id)
            multiNodeResults.overall
          else
            testResults.overall
        Tests.Output(overall,
          testResults.events ++ multiNodeResults.events,
          testResults.summaries ++ multiNodeResults.summaries)
    })

  lazy val formatSettings = SbtScalariform.scalariformSettings ++ Seq(
    ScalariformKeys.preferences in Compile := formattingPreferences,
    ScalariformKeys.preferences in Test := formattingPreferences)

  import scalariform.formatter.preferences._
  def formattingPreferences =
    FormattingPreferences()
      .setPreference(RewriteArrowSymbols, false)
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(DoubleIndentClassDeclaration, true)
      .setPreference(IndentSpaces, 2)

}

