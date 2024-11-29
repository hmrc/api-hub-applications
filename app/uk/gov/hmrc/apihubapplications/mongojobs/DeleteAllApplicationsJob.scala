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

import com.google.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.apihubapplications.models.application.Application
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.services.ApplicationsService
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@Singleton
class DeleteAllApplicationsJob @Inject()(
  applicationsService: ApplicationsService
)(implicit ec: ExecutionContext) extends MongoJob with Logging {

  import DeleteAllApplicationsJob.*

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  override def run(): Future[Unit] = {
    delete().map(_ => ())
  }

  def delete(): Future[MigrationSummary] = {
    applicationsService
      .findAll(None, includeDeleted = false)
      .map(
        applications =>
          applications.map(deleteApplication)
      )
      .flatMap(Future.sequence(_))
      .map(
        _.foldRight(MigrationSummary())((result, summary) => summary.add(result))
      )
      .andThen {
        case Success(summary) =>
          logger.info(s"Delete all applications migration complete: summary=${summary.report}")
        case Failure(e) =>
          logger.error("Delete all applications migration failed", e)
      }
  }

  private def deleteApplication(application: Application):Future[MigrationResult] = {
    applicationsService
      .delete(application.safeId, systemUser)
      .map {
        case Right(_) => Deleted
        case Left(e) =>
          logger.warn(s"Error deleting application ${application.safeId}", e)
          Failed
      }
      .recover {
        case e =>
          logger.warn(s"Error deleting application ${application.safeId}", e)
          Failed
      }
  }

}

object DeleteAllApplicationsJob {

  val systemUser: String = "system"

  sealed trait MigrationResult
  case object Deleted extends MigrationResult
  case object Failed extends MigrationResult

  case class MigrationSummary(applications: Int, failed: Int) {

    def deleted: Int = applications - failed

    def add(result: MigrationResult): MigrationSummary = {
      result match {
        case Deleted => MigrationSummary(applications + 1, failed)
        case Failed => MigrationSummary(applications + 1, failed + 1)
      }
    }

    def report: String = {
      Seq(
        s"applications: $applications",
        s"deleted: $deleted",
        s"failed: $failed"
      ).mkString(", ")
    }

  }

  object MigrationSummary {
    def apply(): MigrationSummary = {
      MigrationSummary(0, 0)
    }
  }

}
