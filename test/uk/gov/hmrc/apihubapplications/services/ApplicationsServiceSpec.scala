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

import org.mockito.{ArgumentMatchers, MockitoSugar}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.models.application.{Application, Creator, Environments, NewApplication, TeamMember}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.Future

class ApplicationsServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar {

  "registerApplication" - {
    "must build the correct application and submit it to the repository" in {
      val clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
      val repository = mock[ApplicationsRepository]
      val service = new ApplicationsService(repository, clock)
      val newApplication = NewApplication("test-name", Creator(email = "test-email"))

      val application = Application(
        id = None,
        name = newApplication.name,
        created = LocalDateTime.now(clock),
        createdBy = newApplication.createdBy,
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = newApplication.createdBy.email)),
        environments = Environments()
      )

      val expected = application.copy(id = Some("test-id"))
      when(repository.insert(ArgumentMatchers.eq(application)))
        .thenReturn(Future.successful(expected))

      service.registerApplication(newApplication) map {
        actual =>
          actual mustBe expected
      }
    }
  }

  "findAll" - {
    "must return all applications from the repository" in {
      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1")),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-2"))
      )

      val repository = mock[ApplicationsRepository]
      when(repository.findAll()).thenReturn(Future.successful(applications))

      val service = new ApplicationsService(repository, Clock.systemDefaultZone())
      service.findAll() map {
        actual =>
          actual mustBe applications
          verify(repository).findAll()
          succeed
      }
    }
  }

}
