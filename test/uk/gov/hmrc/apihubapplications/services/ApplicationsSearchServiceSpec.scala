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

import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{verify, verifyNoInteractions, when}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.{EitherValues, OptionValues}
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.CallError
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationNotFoundException, IdmsException, TeamNotFoundException}
import uk.gov.hmrc.apihubapplications.models.idms.{ClientResponse, ClientScope}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.Future

class ApplicationsSearchServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar with OptionValues with EitherValues {

  import ApplicationsSearchServiceSpec.*

  "findAll" - {
    "must return all applications from the repository" in {
      val fixture = buildFixture
      import fixture.*

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
      import fixture.*

      val teamId = "test-team-id"
      val team = Team(
        id = Some(teamId),
        name = "test-team-name",
        created = Some(LocalDateTime.now(clock)),
        teamMembers = Seq(TeamMember("test-email"))
      )

      val application1 = Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Some(teamId), Seq.empty)
      val application2 = Application(Some("test-id-2"), "test-name-2", Creator("test-email-2"), Seq.empty)
      val applications = Seq(application1, application2)

      when(teamsService.findAll(any)).thenReturn(Future.successful(Seq.empty))
      when(repository.findAll(eqTo(None), eqTo(Seq.empty), eqTo(false))).thenReturn(Future.successful(applications))
      when(teamsService.findById(teamId)).thenReturn(Future.successful(Right(team)))

      val expected = Seq(
        application1.setTeamMembers(team.teamMembers).setTeamName(team.name),
        application2
      )

      service.findAll(None, false) map {
        actual =>
          actual mustBe expected
      }
    }

    "must return an issue if a global team cannot be found" in {
      val fixture = buildFixture
      import fixture.*

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
      import fixture.*

      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq(TeamMember("test-email-1"))),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-1"), Seq(TeamMember("test-email-1")))
      )

      when(teamsService.findAll(any)).thenReturn(Future.successful(Seq.empty))
      when(repository.findAll(any, any, any)).thenReturn(Future.successful(applications))

      service.findAll(Some("test-email-1"), false) map {
        actual =>
          actual mustBe applications
          verify(repository).findAll(eqTo(Some("test-email-1")), eqTo(Seq.empty), eqTo(false))
          succeed
      }
    }

    "must return applications owned by global teams with the named team member" in {
      val fixture = buildFixture
      import fixture.*

      val team = Team("test-team-name", Seq.empty, id = Some("test-team-id-1"), created = Some(LocalDateTime.now(clock)))

      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq(TeamMember("test-email-1"))),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-1"), team.id.value)
      ).map(_.setTeamName(team.name))

      when(teamsService.findAll(any)).thenReturn(Future.successful(Seq(team)))
      when(repository.findAll(any, any, any)).thenReturn(Future.successful(applications))
      when(teamsService.findById(eqTo(team.id.value))).thenReturn(Future.successful(Right(team)))

      service.findAll(Some("test-email-1"), false) map {
        actual =>
          actual mustBe applications
          verify(teamsService).findAll(eqTo(Some("test-email-1")))
          verify(repository).findAll(eqTo(Some("test-email-1")), eqTo(Seq(team)), eqTo(false))
          succeed
      }
    }

    "must return deleted applications when requested" in {
      val fixture = buildFixture
      import fixture.*

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
      import fixture.*

      val apiId = "test-api"
      val apiTitle = "test-api-title"
      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq.empty).addApi(Api(apiId, apiTitle)),
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq.empty).addApi(Api(apiId, apiTitle)),
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
      import fixture.*

      val deleted = Deleted(LocalDateTime.now(clock), "test-deleted-by")
      val apiId = "test-api"
      val apiTitle = "test-api-title"

      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq.empty).addApi(Api(apiId, apiTitle)).delete(deleted),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-2"), Seq.empty).addApi(Api(apiId, apiTitle)).delete(deleted)
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
      import fixture.*

      val teamId = "test-team-id"
      val team = Team(
        id = Some(teamId),
        name = "test-team-name",
        created = Some(LocalDateTime.now(clock)),
        teamMembers = Seq(TeamMember("test-email"))
      )

      val apiId = "test-api"
      val apiTitle = "test-api-title"
      val application1 = Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Some(teamId), Seq.empty).addApi(Api(apiId, apiTitle))
      val application2 = Application(Some("test-id-2"), "test-name-1", Creator("test-email-1"), Seq.empty).addApi(Api(apiId, apiTitle))

      val applications = Seq(application1, application2)

      when(repository.findAllUsingApi(eqTo(apiId), eqTo(false))).thenReturn(Future.successful(applications))
      when(teamsService.findById(eqTo(teamId))).thenReturn(Future.successful(Right(team)))

      val expected = Seq(
        application1.setTeamMembers(team.teamMembers).setTeamName(team.name),
        application2
      )

      service.findAllUsingApi(apiId, false) map {
        actual =>
          actual mustBe expected
      }
    }

    "must return an issue if a global team cannot be found" in {
      val fixture = buildFixture
      import fixture.*

      val teamId = "test-team-id"

      val apiId = "test-api"
      val apiTitle = "test-api-title"
      val application1 = Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Some(teamId), Seq.empty).addApi(Api(apiId, apiTitle))
      val application2 = Application(Some("test-id-2"), "test-name-1", Creator("test-email-1"), Seq.empty).addApi(Api(apiId, apiTitle))

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
      import fixture.*

      val id = "test-id"
      val primaryClientId = "test-primary-client-id"
      val secondaryClientId = "test-secondary-client-id"
      val secondaryClientSecret = "test-secondary-secret-1234"
      val scope1 = "test-scope-1"
      val scope2 = "test-scope-2"
      val scope3 = "test-scope-3"
      val scope4 = "test-scope-4"

      val application = Application(Some(id), "test-name", Creator("test-creator"), Seq.empty, clock)
        .setCredentials(FakeHipEnvironments.productionEnvironment, Seq(Credential(primaryClientId, LocalDateTime.now(clock), None, None, FakeHipEnvironments.productionEnvironment.id)))
        .setCredentials(FakeHipEnvironments.testEnvironment, Seq(Credential(secondaryClientId, LocalDateTime.now(clock), None, None, FakeHipEnvironments.testEnvironment.id)))

      when(repository.findById(eqTo(id), any))
        .thenReturn(Future.successful(Right(application)))

      when(idmsConnector.fetchClient(eqTo(FakeHipEnvironments.testEnvironment), eqTo(secondaryClientId))(any))
        .thenReturn(Future.successful(Right(ClientResponse(secondaryClientId, secondaryClientSecret))))
      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.testEnvironment), eqTo(secondaryClientId))(any))
        .thenReturn(Future.successful(Right(Seq(ClientScope(scope1), ClientScope(scope2)))))
      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.productionEnvironment), eqTo(primaryClientId))(any))
        .thenReturn(Future.successful(Right(Seq(ClientScope(scope3), ClientScope(scope4)))))

      val expected = application

      service.findById(id)(HeaderCarrier()).map {
        result =>
          result mustBe Right(expected)
      }
    }

    "must return the application's team members when it is linked to a global team" in {
      val fixture = buildFixture
      import fixture.*

      val teamId = "test-team-id"
      val team = Team(
        id = Some(teamId),
        name = "test-team-name",
        created = Some(LocalDateTime.now(clock)),
        teamMembers = Seq(TeamMember("test-email"))
      )

      val id = "test-id"
      val application = Application(Some(id), "test-name", Creator("test-creator"), Some(teamId), Seq.empty, clock)
        .setTeamName(team.name)

      when(repository.findById(eqTo(id), any))
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
      import fixture.*

      val id = "test-id"

      when(repository.findById(eqTo(id), any))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(id))))

      service.findById(id)(HeaderCarrier()).map(
        result =>
          result mustBe Left(ApplicationNotFoundException.forId(id))
      )
    }

    "must return an issue when the team does not exist" in {
      val fixture = buildFixture
      import fixture.*

      val teamId = "test-team-id"

      val id = "test-id"
      val application = Application(Some(id), "test-name", Creator("test-creator"), Some(teamId), Seq.empty, clock)

      when(repository.findById(eqTo(id), any))
        .thenReturn(Future.successful(Right(application)))

      when(teamsService.findById(teamId))
        .thenReturn(Future.successful(Left(TeamNotFoundException.forId(teamId))))

      service.findById(id, false)(HeaderCarrier()).map(
        actual =>
          actual.value.issues mustBe Seq(Issues.teamNotFound(teamId, TeamNotFoundException.forId(teamId)))
      )
    }

    "must not attempt to enrich with IDMS data if the application is deleted" in {
      val fixture = buildFixture
      import fixture.*

      val id = "test-id"

      val application = Application(Some(id), "test-name", Creator("test-creator"), Seq.empty, clock).copy(deleted = Some(Deleted(LocalDateTime.now(clock), "test-deleted-by")))
        .setCredentials(FakeHipEnvironments.productionEnvironment, Seq(Credential("test-primary-client-id", LocalDateTime.now(clock), None, None, FakeHipEnvironments.productionEnvironment.id)))
        .setCredentials(FakeHipEnvironments.testEnvironment, Seq(Credential("test-secondary-client-id", LocalDateTime.now(clock), None, None, FakeHipEnvironments.testEnvironment.id)))

      when(repository.findById(eqTo(id), any))
        .thenReturn(Future.successful(Right(application)))

      service.findById(id, true)(HeaderCarrier()).map {
        result =>
          result mustBe Right(application)
          verifyNoInteractions(idmsConnector)
          succeed
      }
    }
  }



  "findByTeamId" - {
    "must return applications when linked to a team" in {
      val fixture = buildFixture
      import fixture.*

      val id = "test-id"
      val teamId = "team-id"
      val team = Team(
        id = Some(teamId),
        name = "test-team-name",
        created = Some(LocalDateTime.now(clock)),
        teamMembers = Seq(TeamMember("test-email"))
      )
      val primaryClientId = "test-primary-client-id"
      val secondaryClientId = "test-secondary-client-id"
      val secondaryClientSecret = "test-secondary-secret-1234"
      val scope1 = "test-scope-1"
      val scope2 = "test-scope-2"
      val scope3 = "test-scope-3"
      val scope4 = "test-scope-4"

      val application = Application(Some(id), "test-name", Creator("test-creator"), Seq.empty, clock)
        .setCredentials(FakeHipEnvironments.productionEnvironment, Seq(Credential(primaryClientId, LocalDateTime.now(clock), None, None, FakeHipEnvironments.productionEnvironment.id)))
        .setCredentials(FakeHipEnvironments.testEnvironment, Seq(Credential(secondaryClientId, LocalDateTime.now(clock), None, None, FakeHipEnvironments.testEnvironment.id)))
        .setTeamId(teamId)

      when(repository.findByTeamId(teamId, true))
        .thenReturn(Future.successful(Seq(application)))

      when(teamsService.findById(teamId))
        .thenReturn(Future.successful(Right(team)))

      when(idmsConnector.fetchClient(eqTo(FakeHipEnvironments.testEnvironment), eqTo(secondaryClientId))(any))
        .thenReturn(Future.successful(Right(ClientResponse(secondaryClientId, secondaryClientSecret))))
      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.testEnvironment), eqTo(secondaryClientId))(any))
        .thenReturn(Future.successful(Right(Seq(ClientScope(scope1), ClientScope(scope2)))))
      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.productionEnvironment), eqTo(primaryClientId))(any))
        .thenReturn(Future.successful(Right(Seq(ClientScope(scope3), ClientScope(scope4)))))

      val expected = application
        .setTeamMembers(Seq(TeamMember("test-email")))
        .setTeamName(team.name)

      service.findByTeamId(teamId, includeDeleted = true)(HeaderCarrier()).map {
        result =>
          result mustBe Seq(expected)
      }
    }

    "must return an empty result when the application does not exist" in {
      val fixture = buildFixture
      import fixture.*

      val id = "test-id"

      when(repository.findByTeamId(eqTo(id), any))
        .thenReturn(Future.successful(Seq.empty[Application]))

      service.findByTeamId(id, includeDeleted = true)(HeaderCarrier()).map(
        result =>
          result mustBe Seq.empty[Application]
      )
    }
  }

  "addTeam" - {
    "must set team members and team name on the application" in {
      val fixture = buildFixture
      import fixture.*

      val id = "test-id"
      val teamId = "team-id"
      val teamName = "team-name"
      val teamMembers = Seq(TeamMember("test-email"))

      val application = Application(Some(id), "test-name", Creator("test-creator"), Seq.empty, clock)
        .setTeamId(teamId)

      val team = Team(
        id = Some(teamId),
        name = teamName,
        teamMembers = teamMembers,
        created = Some(LocalDateTime.now()),
      )

      when(teamsService.findById(eqTo(teamId)))
        .thenReturn(Future.successful(Right(team)))

      service.asInstanceOf[ApplicationsSearchServiceImpl].addTeam(application).map {
        result =>
          result mustBe application.setTeamMembers(teamMembers).setTeamName(teamName)
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
    val service = new ApplicationsSearchServiceImpl(teamsService, repository, idmsConnector, FakeHipEnvironments)
    Fixture(teamsService, repository, idmsConnector, service)
  }

}

object ApplicationsSearchServiceSpec {

  val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

}
