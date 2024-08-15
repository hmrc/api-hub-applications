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

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses._
import uk.gov.hmrc.apihubapplications.models.application.{Application, Creator, Deleted}
import uk.gov.hmrc.apihubapplications.models.exception.NotUpdatedException
import uk.gov.hmrc.apihubapplications.mongojobs.HardDeleteMigrationJob.MigrationSummary
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.AccessRequestsService
import uk.gov.hmrc.apihubapplications.testhelpers.AccessRequestGenerator

import java.time.LocalDateTime
import scala.concurrent.Future

class HardDeleteMigrationJobSpec extends AsyncFreeSpec with Matchers with MockitoSugar with ArgumentMatchersSugar {

  import HardDeleteMigrationJobSpec._

  "HardDeleteMigrationJob" - {
    "must process applications and return the correct summary" in {
      val fixture = buildFixture()

      when(fixture.applicationsRepository.findAll(any, any, any)).thenReturn(Future.successful(applications))

      when(fixture.accessRequestsService.getAccessRequests(any, eqTo(None))).thenReturn(Future.successful(Seq.empty))
      when(fixture.accessRequestsService.getAccessRequests(eqTo(Some(softDeletedApplication.safeId)), eqTo(None))).thenReturn(Future.successful(accessRequests))

      when(fixture.applicationsRepository.delete(any)).thenReturn(Future.successful(Right(())))
      when(fixture.applicationsRepository.delete(eqTo(failedApplication1.safeId))).thenReturn(Future.successful(Left(NotUpdatedException.forApplication(failedApplication1))))
      when(fixture.applicationsRepository.delete(eqTo(failedApplication2.safeId))).thenReturn(Future.failed(new RuntimeException("test-message")))

      fixture.job.migrate().map(
        result =>
          result mustBe MigrationSummary(7, 1, 2, 2)
      )
    }
  }

  private case class Fixture(
    applicationsRepository: ApplicationsRepository,
    accessRequestsService: AccessRequestsService,
    job: HardDeleteMigrationJob
  )

  private def buildFixture(): Fixture = {
    val applicationsRepository = mock[ApplicationsRepository]
    val accessRequestsService = mock[AccessRequestsService]
    val job = new HardDeleteMigrationJob(applicationsRepository, accessRequestsService)

    Fixture(applicationsRepository, accessRequestsService, job)
  }

}

object HardDeleteMigrationJobSpec extends AccessRequestGenerator {

  private val createdBy = Creator("test-email")
  private val team = Seq.empty
  private val deleted = Deleted(LocalDateTime.now(), "test-email")

  private val notDeleted1 = Application(Some("not-deleted-id-1"), "not-deleted-application-1", createdBy, team)
  private val notDeleted2 = Application(Some("not-deleted-id-2"), "not-deleted-application-2", createdBy, team)
  private val softDeletedApplication = Application(Some("soft-deleted-id"), "soft-deleted-application", createdBy, team).delete(deleted)
  private val hardDeletedApplication1 = Application(Some("hard-deleted-id-1"), "hard-deleted-application-1", createdBy, team).delete(deleted)
  private val hardDeletedApplication2 = Application(Some("hard-deleted-id-2"), "hard-deleted-application-2", createdBy, team).delete(deleted)
  private val failedApplication1 = Application(Some("failed-id-1"), "failed-application-1", createdBy, team).delete(deleted)
  private val failedApplication2 = Application(Some("failed-id-2"), "failed-application-2", createdBy, team).delete(deleted)

  private val applications = Seq(notDeleted1, notDeleted2, softDeletedApplication, hardDeletedApplication1, hardDeletedApplication2, failedApplication1, failedApplication2)

  private val accessRequests = Seq(sampleAccessRequest())

}
