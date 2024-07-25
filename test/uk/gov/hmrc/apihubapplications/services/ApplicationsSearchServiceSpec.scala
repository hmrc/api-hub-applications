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

package uk.gov.hmrc.apihubapplications.services

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.{Application, Creator, Deleted, TeamMember}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses._
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.Future

class ApplicationsSearchServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar with ArgumentMatchersSugar {

  import ApplicationsSearchServiceSpec._

  "findAll" - {
    "must return all applications from the repository" in {
      val fixture = buildFixture
      import fixture._

      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq.empty),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-2"), Seq.empty)
      )

      when(repository.findAll(any, any)).thenReturn(Future.successful(applications))

      service.findAll(None, false) map {
        actual =>
          actual mustBe applications
          verify(repository).findAll(eqTo(None), eqTo(false))
          succeed
      }
    }

    "must return all applications from the repository for named team member" in {
      val fixture = buildFixture
      import fixture._

      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq(TeamMember("test-email-1"))),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-1"), Seq(TeamMember("test-email-1")))
      )

      when(repository.findAll(any, any)).thenReturn(Future.successful(applications))

      service.findAll(Some("test-email-1"), false) map {
        actual =>
          actual mustBe applications
          verify(repository).findAll(eqTo(Some("test-email-1")), eqTo(false))
          succeed
      }
    }

    "must return deleted applications when requested" in {
      val fixture = buildFixture
      import fixture._

      val deleted = Deleted(LocalDateTime.now(clock), "test-deleted-by")

      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq.empty).delete(deleted),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-2"), Seq.empty).delete(deleted)
      )

      when(repository.findAll(any, any)).thenReturn(Future.successful(applications))

      service.findAll(None, true) map {
        actual =>
          actual mustBe applications
          verify(repository).findAll(eqTo(None), eqTo(true))
          succeed
      }
    }
  }

  private case class Fixture(
    repository: ApplicationsRepository,
    idmsConnector: IdmsConnector,
    service: ApplicationsSearchService
  )

  private def buildFixture: Fixture = {
    val repository = mock[ApplicationsRepository]
    val idmsConnector = mock[IdmsConnector]
    val service = new ApplicationsSearchServiceImpl(repository, idmsConnector)
    Fixture(repository, idmsConnector, service)
  }

}

object ApplicationsSearchServiceSpec {

  val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

}
