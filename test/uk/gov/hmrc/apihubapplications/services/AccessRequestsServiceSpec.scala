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
import uk.gov.hmrc.apihubapplications.repositories.AccessRequestsRepository
import uk.gov.hmrc.apihubapplications.testhelpers.AccessRequestGenerator

import java.time.{Clock, Instant, ZoneId}
import scala.concurrent.Future

class AccessRequestsServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar with AccessRequestGenerator {

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

  private case class Fixture(clock: Clock, repository: AccessRequestsRepository, accessRequestsService: AccessRequestsService)

  private def buildFixture(): Fixture = {
    val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
    val repository = mock[AccessRequestsRepository]
    val accessRequestsService = new AccessRequestsService(repository, clock)
    Fixture(clock, repository, accessRequestsService)
  }

}
