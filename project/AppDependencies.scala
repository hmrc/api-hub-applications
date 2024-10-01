import play.core.PlayVersion
import play.sbt.PlayImport._
import sbt.Keys.libraryDependencies
import sbt._

object AppDependencies {

  private val bootstrapVersion = "9.5.0"
  private val hmrcMongoVersion = "2.2.0"
  private val internalAuthVersion = "3.0.0"

  val compile = Seq(
    "uk.gov.hmrc"             %% "bootstrap-backend-play-30"    % bootstrapVersion,
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-metrix-play-30"    % hmrcMongoVersion,
    "uk.gov.hmrc"             %% "internal-auth-client-play-30" % internalAuthVersion,
    "uk.gov.hmrc"             %% "crypto-json-play-30"          % "8.1.0"
  )

  val test = Seq(
    "uk.gov.hmrc"             %% "bootstrap-test-play-30"     % bootstrapVersion            % Test,
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-test-play-30"    % hmrcMongoVersion            % Test,
    "org.scalatestplus"       %% "mockito-4-11"               % "3.2.17.0"                  % Test,
    "org.scalacheck"          %% "scalacheck"                 % "1.18.0"                    % Test,
    "org.scalatestplus"       %% "scalacheck-1-17"            % "3.2.18.0"                  % Test
  )

  val it = Seq.empty

}
