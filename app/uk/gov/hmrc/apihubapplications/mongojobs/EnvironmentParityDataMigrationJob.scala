/*
 * Copyright 2024 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.apihubapplications.mongojobs

import cats.data.EitherT
import com.google.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.apihubapplications.config.HipEnvironments
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.Application
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.ApplicationsService
import uk.gov.hmrc.apihubapplications.services.helpers.Helpers.{ignoreClientNotFound, useFirstException}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

@Singleton
class EnvironmentParityDataMigrationJob @Inject()(
  applicationsService: ApplicationsService,
  applicationsRepository: ApplicationsRepository,
  hipEnvironments: HipEnvironments,
  idmsConnector: IdmsConnector
)(implicit ec: ExecutionContext) extends MongoJob with Logging {

  import EnvironmentParityDataMigrationJob._

  private implicit val headerCarrier: HeaderCarrier = HeaderCarrier()

  override def run(): Future[Unit] = {
    migrate().map(_ => ())
  }

  def migrate(): Future[MigrationSummary] = {
    applicationsService.findAll(None, includeDeleted = true)
      .map(_.map(deleteApplication))
      .flatMap(Future.sequence(_))
      .map(
        _.foldRight(MigrationSummary())((result, summary) => summary.add(result))
      )
      .andThen {
        case Success(summary) =>
          logger.info(s"Applications migration complete: summary=${summary.report}")
        case Failure(e) =>
          logger.error("Applications migration failed", e)
      }
  }

  private def deleteApplication(application: Application):Future[MigrationResult] = {
    (for {
      application <- EitherT(deleteHiddenCredentials(application))
      saved <- EitherT(updateApplication(application))
    } yield Migrated)
      .merge
      .recover {
        case NonFatal(e) =>
          logger.warn(s"Error deleting application ${application.safeId}", e)
          Failed
      }
  }

  private def deleteHiddenCredentials(application: Application): Future[Either[MigrationResult, Application]] = {
    if (!application.isDeleted) {
      Future.sequence(
          application.credentials
            .filter(_.isHidden)
            .toSeq
            .map(
              credential =>
                idmsConnector.deleteClient(
                  hipEnvironment = hipEnvironments.forId(credential.environmentId),
                  clientId = credential.clientId
                )
            )
        )
        .map(ignoreClientNotFound)
        .map(useFirstException)
        .map {
          case Right(_) => Right(removeHiddenCredentials(application))
          case Left(e) => Left(IdsmFailed)
        }
    }
    else {
      Future.successful(Right(removeHiddenCredentials(application)))
    }
  }

  private def removeHiddenCredentials(application: Application): Application = {
    hipEnvironments.environments.foldLeft(application)(
      (application, hipEnvironment) =>
        application.setCredentials(
          hipEnvironment = hipEnvironment,
          credentials = application.getCredentials(hipEnvironment).filterNot(_.isHidden)
        )
    )
  }

  private def updateApplication(application: Application): Future[Either[MigrationResult, Unit]] = {
    applicationsRepository.update(application).map {
      case Right(_) => Right(())
      case Left(e) =>
        logger.warn(s"Unable to update application ${application.safeId}", e)
        Left(UpdateFailed)
    }
  }

}

object EnvironmentParityDataMigrationJob {

  sealed trait MigrationResult
  case object Migrated extends MigrationResult
  case object IdsmFailed extends MigrationResult
  case object UpdateFailed extends MigrationResult
  case object Failed extends MigrationResult

  case class MigrationSummary(applications: Int, failed: Int, idmsFailed: Int, updateFailed: Int) {

    def migrated: Int = applications - failed

    def add(result: MigrationResult): MigrationSummary = {
      result match {
        case Migrated => MigrationSummary(applications + 1, failed, idmsFailed, updateFailed)
        case IdsmFailed => MigrationSummary(applications + 1, failed, idmsFailed + 1, updateFailed)
        case UpdateFailed => MigrationSummary(applications + 1, failed, idmsFailed, updateFailed + 1)
        case Failed => MigrationSummary(applications + 1, failed + 1, idmsFailed, updateFailed)
      }
    }

    def report: String = {
      Seq(
        s"Applications: $applications",
        s"Migrated: $migrated",
        s"Failed: $failed",
        s"IDMS failed: $idmsFailed",
        s"Update failed: $updateFailed"
      ).mkString(", ")
    }

  }

  object MigrationSummary {

    def apply(): MigrationSummary = {
      MigrationSummary(0, 0, 0, 0)
    }

  }

}
