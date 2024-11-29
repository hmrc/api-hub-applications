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

import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.when
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application.{Application, Creator}
import uk.gov.hmrc.apihubapplications.models.exception.NotUpdatedException
import uk.gov.hmrc.apihubapplications.mongojobs.DeleteAllApplicationsJob.MigrationSummary
import uk.gov.hmrc.apihubapplications.services.ApplicationsService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DeleteAllApplicationsJobSpec extends AsyncFreeSpec with Matchers with MockitoSugar {

  import DeleteAllApplicationsJobSpec.*

  "DeleteAllApplicationsJob" - {
    "must process applications and return the correct summary" in {
      val fixture = buildFixture()

      when(fixture.applicationsService.findAll(eqTo(None), eqTo(false)))
        .thenReturn(Future.successful(Seq(application1, application2, application3)))

      when(fixture.applicationsService.delete(eqTo(application1.safeId), eqTo(DeleteAllApplicationsJob.systemUser))(any))
        .thenReturn(Future.successful(Right(())))

      when(fixture.applicationsService.delete(eqTo(application2.safeId), eqTo(DeleteAllApplicationsJob.systemUser))(any))
        .thenReturn(Future.successful(Right(())))

      when(fixture.applicationsService.delete(eqTo(application3.safeId), eqTo(DeleteAllApplicationsJob.systemUser))(any))
        .thenReturn(Future.successful(Left(NotUpdatedException.forApplication(application3))))

      fixture.job.delete().map(
        result =>
          result mustBe MigrationSummary(3, 1)
      )
    }
  }

}

object DeleteAllApplicationsJobSpec extends MockitoSugar {

  private case class Fixture(applicationsService: ApplicationsService, job: DeleteAllApplicationsJob)

  private def buildFixture(): Fixture = {
    val applicationsService = mock[ApplicationsService]
    val job = new DeleteAllApplicationsJob(applicationsService)

    Fixture(applicationsService, job)
  }

  private val createdBy = Creator("test-email")
  private val team = Seq.empty

  private val application1 = Application(Some("test-id-1"), "test-application-1", createdBy, team)
  private val application2 = Application(Some("test-id-2"), "test-application-2", createdBy, team)
  private val application3 = Application(Some("test-id-3"), "test-application-3", createdBy, team)

}
