/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.apihubapplications.services

import org.mockito.ArgumentMatchers.any
import org.mockito.{ArgumentMatchers, MockitoSugar}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, AccessRequestStatus, Pending}
import uk.gov.hmrc.apihubapplications.repositories.AccessRequestsRepository
import uk.gov.hmrc.apihubapplications.testhelpers.AccessRequestGenerator

import java.time.{Clock, Instant, ZoneId}
import scala.concurrent.Future

class AccessRequestsServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar with AccessRequestGenerator with TableDrivenPropertyChecks {

  "createAccessRequest" - {
    "must pass the correct requests to the repository" in {
      val fixture = buildFixture()

      val request = sampleAccessRequestRequest()
      val expected = sampleAccessRequests()

      when(fixture.repository.insert(any())).thenReturn(Future.successful(expected))

      fixture.accessRequestsService.createAccessRequest(request).map {
        result =>
          verify(fixture.repository).insert(ArgumentMatchers.eq(request.toAccessRequests(fixture.clock)))
          result mustBe expected
      }
    }
  }

  "getAccessRequests" - {
    "must request the correct access requests from the repository" in {
      val filters = Table(
        ("Application Id", "Status"),
        (Some("test-application-id"), Some(Pending)),
        (Some("test-application-id"), None),
        (None, Some(Pending)),
        (None, None)
      )

      val fixture = buildFixture()

      forAll(filters) {(applicationIdFilter: Option[String], statusFilter: Option[AccessRequestStatus]) =>
        val expected = sampleAccessRequests()
        when(fixture.repository.find(any(), any())).thenReturn(Future.successful(expected))

        fixture.accessRequestsService.getAccessRequests(applicationIdFilter, statusFilter).map {
          actual =>
            verify(fixture.repository).find(ArgumentMatchers.eq(applicationIdFilter), ArgumentMatchers.eq(statusFilter))
            actual mustBe expected
        }
      }
    }
  }

  "getAccessRequest" - {
    "must request the correct access request from the repository" in {
      val accessRequests = Table(
        ("Id", "Access Request"),
        ("test-id-1", Some(sampleAccessRequest())),
        ("test-id-2", None)
      )

      val fixture = buildFixture()

      forAll(accessRequests) {(id: String, accessRequest: Option[AccessRequest]) =>
        when(fixture.repository.findById(any())).thenReturn(Future.successful(accessRequest))

        fixture.accessRequestsService.getAccessRequest(id).map {
          actual =>
            verify(fixture.repository).findById(ArgumentMatchers.eq(id))
            actual mustBe accessRequest
        }
      }
    }
  }

  private case class Fixture(clock: Clock, repository: AccessRequestsRepository, accessRequestsService: AccessRequestsService)

  private def buildFixture(): Fixture = {
    val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
    val repository = mock[AccessRequestsRepository]
    val accessRequestsService = new AccessRequestsService(repository, clock)
    Fixture(clock, repository, accessRequestsService)
  }

}
