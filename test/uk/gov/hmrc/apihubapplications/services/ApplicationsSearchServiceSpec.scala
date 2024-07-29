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
import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.{Api, Application, Creator, Credential, Deleted, Issues, Primary, Scope, Secondary, TeamMember}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses._
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationNotFoundException, IdmsException, TeamNotFoundException}
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.CallError
import uk.gov.hmrc.apihubapplications.models.idms.{ClientResponse, ClientScope}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.Future

class ApplicationsSearchServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar with ArgumentMatchersSugar with OptionValues with EitherValues {

  import ApplicationsSearchServiceSpec._

  "findAll" - {
    "must return all applications from the repository" in {
      val fixture = buildFixture
      import fixture._

      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq.empty),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-2"), Seq.empty)
      )

      when(teamsService.findAll(any)).thenReturn(Future.successful(Seq.empty))
      when(repository.findAll(any, any, any)).thenReturn(Future.successful(applications))

      service.findAll(None, false) map {
        actual =>
          actual mustBe applications
          verify(repository).findAll(eqTo(None), eqTo(Seq.empty), eqTo(false))
          succeed
      }
    }

    "must return team members for applications linked to global teams" in {
      val fixture = buildFixture
      import fixture._

      val teamId = "test-team-id"
      val team = Team(
        id = Some(teamId),
        name = "test-team-name",
        created = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember("test-email"))
      )

      val application1 = Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Some(teamId), Seq.empty)
      val application2 = Application(Some("test-id-2"), "test-name-2", Creator("test-email-2"), Seq.empty)
      val applications = Seq(application1, application2)

      when(teamsService.findAll(any)).thenReturn(Future.successful(Seq.empty))
      when(repository.findAll(eqTo(None), eqTo(Seq.empty), eqTo(false))).thenReturn(Future.successful(applications))
      when(teamsService.findById(teamId)).thenReturn(Future.successful(Right(team)))

      val expected = Seq(
        application1.setTeamMembers(team.teamMembers),
        application2
      )

      service.findAll(None, false) map {
        actual =>
          actual mustBe expected
      }
    }

    "must return an issue if a global team cannot be found" in {
      val fixture = buildFixture
      import fixture._

      val teamId = "test-team-id"

      val application1 = Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Some(teamId), Seq.empty)
      val application2 = Application(Some("test-id-2"), "test-name-2", Creator("test-email-2"), Seq.empty)
      val applications = Seq(application1, application2)

      when(teamsService.findAll(any)).thenReturn(Future.successful(Seq.empty))
      when(repository.findAll(eqTo(None), eqTo(Seq.empty), eqTo(false))).thenReturn(Future.successful(applications))
      when(teamsService.findById(teamId)).thenReturn(Future.successful(Left(TeamNotFoundException.forId(teamId))))

      val expected = Seq(
        application1.addIssue(Issues.teamNotFound(teamId, TeamNotFoundException.forId(teamId))),
        application2
      )

      service.findAll(None, false) map {
        actual =>
          actual mustBe expected
      }
    }

    "must return all applications from the repository for named team member" in {
      val fixture = buildFixture
      import fixture._

      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq(TeamMember("test-email-1"))),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-1"), Seq(TeamMember("test-email-1")))
      )

      when(teamsService.findAll(any)).thenReturn(Future.successful(Seq.empty))
      when(repository.findAll(any, any, any)).thenReturn(Future.successful(applications))

      service.findAll(Some("test-email-1"), false) map {
        actual =>
          actual mustBe applications
          verify(repository).findAll(eqTo(Some("test-email-1")), eqTo(Seq.empty),  eqTo(false))
          succeed
      }
    }

    "must return applications owned by global teams with the named team member" in {
      val fixture = buildFixture
      import fixture._

      val team = Team(Some("test-team-id-1"), "test-team-name", LocalDateTime.now(clock), Seq.empty)

      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq(TeamMember("test-email-1"))),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-1"), team.id.value)
      )

      when(teamsService.findAll(any)).thenReturn(Future.successful(Seq(team)))
      when(repository.findAll(any, any, any)).thenReturn(Future.successful(applications))
      when(teamsService.findById(eqTo(team.id.value))).thenReturn(Future.successful(Right(team)))

      service.findAll(Some("test-email-1"), false) map {
        actual =>
          actual mustBe applications
          verify(teamsService).findAll(eqTo(Some("test-email-1")))
          verify(repository).findAll(eqTo(Some("test-email-1")), eqTo(Seq(team)),  eqTo(false))
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

      when(teamsService.findAll(any)).thenReturn(Future.successful(Seq.empty))
      when(repository.findAll(any, any, any)).thenReturn(Future.successful(applications))

      service.findAll(None, true) map {
        actual =>
          actual mustBe applications
          verify(repository).findAll(eqTo(None), eqTo(Seq.empty), eqTo(true))
          succeed
      }
    }
  }

  "findAllUsingApi" - {
    "must return all applications from the repository that have a given API and are not deleted" in {
      val fixture = buildFixture
      import fixture._

      val apiId = "test-api"
      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq.empty).addApi(Api(apiId)),
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq.empty).addApi(Api(apiId)),
      )

      when(repository.findAllUsingApi(any, any)).thenReturn(Future.successful(applications))

      service.findAllUsingApi(apiId, false) map {
        actual =>
          actual mustBe applications
          verify(repository).findAllUsingApi(eqTo(apiId), eqTo(false))
          succeed
      }
    }

    "must return deleted applications when requested" in {
      val fixture = buildFixture
      import fixture._

      val deleted = Deleted(LocalDateTime.now(clock), "test-deleted-by")
      val apiId = "test-api"

      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq.empty).addApi(Api(apiId)).delete(deleted),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-2"), Seq.empty).addApi(Api(apiId)).delete(deleted)
      )

      when(repository.findAllUsingApi(any, any)).thenReturn(Future.successful(applications))

      service.findAllUsingApi(apiId, true) map {
        actual =>
          actual mustBe applications
          verify(repository).findAllUsingApi(eqTo(apiId), eqTo(true))
          succeed
      }
    }

    "must return team members for applications linked to global teams" in {
      val fixture = buildFixture
      import fixture._

      val teamId = "test-team-id"
      val team = Team(
        id = Some(teamId),
        name = "test-team-name",
        created = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember("test-email"))
      )

      val apiId = "test-api"
      val application1 = Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Some(teamId), Seq.empty).addApi(Api(apiId))
      val application2 = Application(Some("test-id-2"), "test-name-1", Creator("test-email-1"), Seq.empty).addApi(Api(apiId))

      val applications = Seq(application1, application2)

      when(repository.findAllUsingApi(eqTo(apiId), eqTo(false))).thenReturn(Future.successful(applications))
      when(teamsService.findById(eqTo(teamId))).thenReturn(Future.successful(Right(team)))

      val expected = Seq(
        application1.setTeamMembers(team.teamMembers),
        application2
      )

      service.findAllUsingApi(apiId, false) map {
        actual =>
          actual mustBe expected
      }
    }

    "must return an issue if a global team cannot be found" in {
      val fixture = buildFixture
      import fixture._

      val teamId = "test-team-id"

      val apiId = "test-api"
      val application1 = Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Some(teamId), Seq.empty).addApi(Api(apiId))
      val application2 = Application(Some("test-id-2"), "test-name-1", Creator("test-email-1"), Seq.empty).addApi(Api(apiId))

      val applications = Seq(application1, application2)

      when(repository.findAllUsingApi(eqTo(apiId), eqTo(false))).thenReturn(Future.successful(applications))
      when(teamsService.findById(eqTo(teamId))).thenReturn(Future.successful(Left(TeamNotFoundException.forId(teamId))))

      val expected = Seq(
        application1.addIssue(Issues.teamNotFound(teamId, TeamNotFoundException.forId(teamId))),
        application2
      )

      service.findAllUsingApi(apiId, false) map {
        actual =>
          actual mustBe expected
      }
    }
  }

  "findById" - {
    "must return the application when it exists" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val primaryClientId = "test-primary-client-id"
      val secondaryClientId = "test-secondary-client-id"
      val secondaryClientSecret = "test-secondary-secret-1234"
      val scope1 = "test-scope-1"
      val scope2 = "test-scope-2"
      val scope3 = "test-scope-3"
      val scope4 = "test-scope-4"

      val application = Application(Some(id), "test-name", Creator("test-creator"), Seq.empty, clock)
        .setPrimaryCredentials(Seq(Credential(primaryClientId, LocalDateTime.now(clock), None, None)))
        .setSecondaryCredentials(Seq(Credential(secondaryClientId, LocalDateTime.now(clock), None, None)))

      when(repository.findById(eqTo(id)))
        .thenReturn(Future.successful(Right(application)))

      when(idmsConnector.fetchClient(eqTo(Secondary), eqTo(secondaryClientId))(any))
        .thenReturn(Future.successful(Right(ClientResponse(secondaryClientId, secondaryClientSecret))))
      when(idmsConnector.fetchClientScopes(eqTo(Secondary), eqTo(secondaryClientId))(any))
        .thenReturn(Future.successful(Right(Seq(ClientScope(scope1), ClientScope(scope2)))))
      when(idmsConnector.fetchClientScopes(eqTo(Primary), eqTo(primaryClientId))(any))
        .thenReturn(Future.successful(Right(Seq(ClientScope(scope3), ClientScope(scope4)))))

      val expected = application
        .setSecondaryCredentials(Seq(Credential(secondaryClientId, LocalDateTime.now(clock), Some(secondaryClientSecret), Some("1234"))))
        .setSecondaryScopes(Seq(Scope(scope1), Scope(scope2)))
        .setPrimaryScopes(Seq(Scope(scope3), Scope(scope4)))

      service.findById(id, enrich = true)(HeaderCarrier()).map {
        result =>
          result mustBe Right(expected)
      }
    }

    "must return the application's team members when it is linked to a global team" in {
      val fixture = buildFixture
      import fixture._

      val teamId = "test-team-id"
      val team = Team(
        id = Some(teamId),
        name = "test-team-name",
        created = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember("test-email"))
      )

      val id = "test-id"
      val application = Application(Some(id), "test-name", Creator("test-creator"), Some(teamId), Seq.empty, clock)

      when(repository.findById(eqTo(id)))
        .thenReturn(Future.successful(Right(application)))

      when(teamsService.findById(teamId))
        .thenReturn(Future.successful(Right(team)))

      val expected = application.setTeamMembers(team.teamMembers)

      service.findById(id, false)(HeaderCarrier()).map(
        actual =>
          actual mustBe Right(expected)
      )
    }

    "must return ApplicationNotFoundException when the application does not exist" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"

      when(repository.findById(eqTo(id)))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(id))))

      service.findById(id, enrich = true)(HeaderCarrier()).map(
        result =>
          result mustBe Left(ApplicationNotFoundException.forId(id))
      )
    }

    "must return an issue when the team does not exist" in {
      val fixture = buildFixture
      import fixture._

      val teamId = "test-team-id"

      val id = "test-id"
      val application = Application(Some(id), "test-name", Creator("test-creator"), Some(teamId), Seq.empty, clock)

      when(repository.findById(eqTo(id)))
        .thenReturn(Future.successful(Right(application)))

      when(teamsService.findById(teamId))
        .thenReturn(Future.successful(Left(TeamNotFoundException.forId(teamId))))

      service.findById(id, false)(HeaderCarrier()).map(
        actual =>
          actual.value.issues mustBe Seq(Issues.teamNotFound(teamId, TeamNotFoundException.forId(teamId)))
      )
    }

    "must not return IdmsException when that is returned from the IDMS connector and return issues instead" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId = "test-client-id"

      val application = Application(Some(id), "test-name", Creator("test-creator"), Seq.empty, clock)
        .setSecondaryCredentials(Seq(Credential(clientId, LocalDateTime.now(clock), None, None)))

      val application1WithIssues = application.copy(issues = Seq("Secondary credential not found. test-message"))

      when(repository.findById(eqTo(id)))
        .thenReturn(Future.successful(Right(application)))

      when(idmsConnector.fetchClient(eqTo(Secondary), eqTo(clientId))(any))
        .thenReturn(Future.successful(Left(IdmsException("test-message", CallError))))
      when(idmsConnector.fetchClientScopes(eqTo(Secondary), eqTo(clientId))(any))
        .thenReturn(Future.successful(Right(Seq.empty)))

      service.findById(id, enrich = true)(HeaderCarrier()).map {
        result => result mustBe Right(application1WithIssues)
      }
    }

    "must not enrich with IDMS data unless asked to" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"

      val application = Application(Some(id), "test-name", Creator("test-creator"), Seq.empty, clock)
        .setPrimaryCredentials(Seq(Credential("test-primary-client-id", LocalDateTime.now(clock), None, None)))
        .setSecondaryCredentials(Seq(Credential("test-secondary-client-id", LocalDateTime.now(clock), None, None)))

      when(repository.findById(eqTo(id)))
        .thenReturn(Future.successful(Right(application)))

      service.findById(id, enrich = false)(HeaderCarrier()).map {
        result =>
          result mustBe Right(application)
          verifyZeroInteractions(idmsConnector)
          succeed
      }
    }
  }

  private case class Fixture(
    teamsService: TeamsService,
    repository: ApplicationsRepository,
    idmsConnector: IdmsConnector,
    service: ApplicationsSearchService
  )

  private def buildFixture: Fixture = {
    val teamsService = mock[TeamsService]
    val repository = mock[ApplicationsRepository]
    val idmsConnector = mock[IdmsConnector]
    val service = new ApplicationsSearchServiceImpl(teamsService, repository, idmsConnector)
    Fixture(teamsService, repository, idmsConnector, service)
  }

}

object ApplicationsSearchServiceSpec {

  val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

}
