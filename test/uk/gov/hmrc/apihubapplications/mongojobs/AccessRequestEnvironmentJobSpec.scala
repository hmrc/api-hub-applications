/*
 * Copyright 2025 HM Revenue & Customs
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
import org.mockito.Mockito.{verify, verifyNoMoreInteractions, when}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.config.HipEnvironments
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, Pending}
import uk.gov.hmrc.apihubapplications.models.exception.NotUpdatedException
import uk.gov.hmrc.apihubapplications.mongojobs.AccessRequestEnvironmentJob.MigrationSummary
import uk.gov.hmrc.apihubapplications.mongojobs.AccessRequestEnvironmentJobSpec.{Fixture, buildFixture, failedAccessRequest, nothingToDoAccessRequest, processedAccessRequest}
import uk.gov.hmrc.apihubapplications.repositories.AccessRequestsRepository
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AccessRequestEnvironmentJobSpec extends AsyncFreeSpec with Matchers with MockitoSugar {

  "AccessRequestEnvironmentJob" - {
    "must process an empty list of access requests and return the correct summary" in {
      val fixture = buildFixture()

      when(fixture.repository.find(any, any)).thenReturn(Future.successful(Seq.empty))

      fixture.job.setEnvironment().map {
        result =>
          verify(fixture.repository).find(eqTo(None), eqTo(None))
          result mustBe MigrationSummary()
      }
    }

    "must process a list of access requests and return the correct summary" in {
      val fixture = buildFixture()

      when(fixture.repository.find(any, any))
        .thenReturn(Future.successful(Seq(
          processedAccessRequest,
          nothingToDoAccessRequest,
          failedAccessRequest
        )))

      when(fixture.repository.update(any))
        .thenReturn(Future.successful(Right(())))

      when(fixture.repository.update(eqTo(updatedAccessRequest(fixture, failedAccessRequest))))
        .thenReturn(Future.successful(Left(NotUpdatedException.forAccessRequest(failedAccessRequest))))

      fixture.job.setEnvironment().map {
        result =>
          verify(fixture.repository).find(eqTo(None), eqTo(None))
          verify(fixture.repository).update(eqTo(updatedAccessRequest(fixture, processedAccessRequest)))
          verify(fixture.repository).update(eqTo(updatedAccessRequest(fixture, failedAccessRequest)))
          verifyNoMoreInteractions(fixture.repository)
          result mustBe MigrationSummary(3, 1, 1)
      }
    }
  }

  private def updatedAccessRequest(fixture: Fixture, accessRequest: AccessRequest): AccessRequest = {
    accessRequest.copy(
      environmentId = Some(fixture.hipEnvironments.production.id)
    )
  }

}

private object AccessRequestEnvironmentJobSpec extends MockitoSugar {

  case class Fixture(
    repository: AccessRequestsRepository,
    hipEnvironments: HipEnvironments,
    job: AccessRequestEnvironmentJob
  )

  def buildFixture(): Fixture = {
    val repository = mock[AccessRequestsRepository]
    val job = new AccessRequestEnvironmentJob(repository, FakeHipEnvironments)
    Fixture(repository, FakeHipEnvironments, job)
  }

  val processedAccessRequest: AccessRequest = buildAccessRequest(1, None)
  val nothingToDoAccessRequest: AccessRequest = buildAccessRequest(2, Some(FakeHipEnvironments.production.id))
  val failedAccessRequest: AccessRequest = buildAccessRequest(3, None)

  def buildAccessRequest(index: Int, environmentId: Option[String]): AccessRequest = {
    AccessRequest(
      id = Some(s"test-id-$index"),
      applicationId = "test-application-id",
      apiId = "test-api-id",
      apiName = "test-api-name",
      status = Pending,
      endpoints = Seq.empty,
      supportingInformation = "test-supporting-information",
      requested = LocalDateTime.now(),
      requestedBy = "test-requested-by",
      decision = None,
      cancelled = None,
      environmentId = environmentId
    )
  }

}
