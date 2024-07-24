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

import org.mockito.MockitoSugar
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.models.application.{Application, Creator, Deleted, TeamMember}

import java.time.LocalDateTime
import scala.concurrent.Future

class UsersServiceSpec
  extends AsyncFreeSpec
  with Matchers
  with MockitoSugar {

  import UsersServiceSpec._

  "findAll" - {
    "must return an empty list if no emails are found in the repo" in {
      val fixture = buildFixture()

      when(fixture.searchService.findAll(true)).thenReturn(Future.successful(Seq.empty))

      fixture.service.findAll().map {
        result => result mustBe Seq.empty
      }
    }

    "must remove duplicate emails from list ignoring domain case" in {
      val fixture = buildFixture()
      val applications = Seq(
        applicationWithTeamMembers(lowerCaseDomainEmail, upperCaseDomainEmail, lowerCaseMailboxEmail, upperCaseMailboxEmail),
        applicationWithTeamMembers(lowerCaseDomainEmail, upperCaseDomainEmail, lowerCaseMailboxEmail, upperCaseMailboxEmail)
      )

      when(fixture.searchService.findAll(true)).thenReturn(Future.successful(applications))

      fixture.service.findAll().map {
        result => result.map(_.email) mustBe Seq(lowerCaseDomainEmail, lowerCaseMailboxEmail, upperCaseMailboxEmail)
      }
    }

    "must return emails in sorted order ignoring case" in {
      val fixture = buildFixture()
      val applications = Seq(
        applicationWithTeamMembers("user3@example.com"),
        applicationWithTeamMembers("uSer1@example.org"),
        applicationWithTeamMembers("USER2@example.com"),
        applicationWithTeamMembers("user2@EXAMPLE.com"),
        applicationWithTeamMembers("user1@example.com"),
      )

      when(fixture.searchService.findAll(true)).thenReturn(Future.successful(applications))

      fixture.service.findAll().map {
        result => result.map(_.email) mustBe Seq(
          "user1@example.com", "uSer1@example.org", "USER2@example.com", "user2@example.com", "user3@example.com"
        )
      }
    }

    "must parse email into parts correctly" in {
      val fixture = buildFixture()
      val applications = Seq(
        applicationWithTeamMembers("USER1@USER2@EXAMPLE.COM"),
      )

      when(fixture.searchService.findAll(true)).thenReturn(Future.successful(applications))

      fixture.service.findAll().map {
        result => result.map(_.email) mustBe Seq("USER1@USER2@example.com")
      }
    }

    "must skip invalid emails" in {
      val fixture = buildFixture()
      val applications = Seq(
        applicationWithTeamMembers(validEmail1, invalidEmail1, validEmail2),
        applicationWithTeamMembers(invalidEmail2, invalidEmail3),
        applicationWithTeamMembers(validEmail3),
      )

      when(fixture.searchService.findAll(true)).thenReturn(Future.successful(applications))

      fixture.service.findAll().map {
        result => result.map(_.email) mustBe Seq(validEmail1, validEmail2, validEmail3)
      }
    }

    "must return emails from all applications included deleted ones" in {
      val fixture = buildFixture()
      val applications = Seq(
        applicationWithTeamMembers(validEmail1),
        applicationWithTeamMembers(validEmail2).copy(deleted = Some(Deleted(LocalDateTime.now(), "deleted"))),
        applicationWithTeamMembers(validEmail3),
      )

      when(fixture.searchService.findAll(true)).thenReturn(Future.successful(applications))

      fixture.service.findAll().map {
        result => result.map(_.email) mustBe Seq(
          validEmail1, validEmail2, validEmail3
        )
      }
    }
  }

  private case class Fixture(searchService: ApplicationsSearchService, service: UsersService)

  private def buildFixture(): Fixture = {
    val searchService = mock[ApplicationsSearchService]

    val service = new UsersService(searchService)

    Fixture(searchService, service)
  }
}

object UsersServiceSpec {
  def applicationWithTeamMembers(emails: String*): Application = {
    val teamMembers = emails.map(email => TeamMember(email))
    Application(None, "Test Application", Creator("creator@example.com"), None, teamMembers)
  }

  val invalidEmail1 = "invalid-email"
  val invalidEmail2 = "@example.com"
  val invalidEmail3 = "invalid@"
  val lowerCaseDomainEmail = "user1@example.com"
  val upperCaseDomainEmail = "user1@EXAMPLE.COM"
  val lowerCaseMailboxEmail = "user2@example.com"
  val upperCaseMailboxEmail = "USER2@example.com"
  val validEmail1 = "user1@example.com"
  val validEmail2 = "user2@example.com"
  val validEmail3 = "user3@example.com"
}
