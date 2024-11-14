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

import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.{times, verify, when}
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatestplus.mockito.MockitoSugar
import play.api.http.Status.BAD_REQUEST
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, EmailConnector, IntegrationCatalogueConnector}
import uk.gov.hmrc.apihubapplications.models.api.ApiTeam
import uk.gov.hmrc.apihubapplications.models.apim.*
import uk.gov.hmrc.apihubapplications.models.application.{Primary, Secondary, TeamMember}
import uk.gov.hmrc.apihubapplications.models.exception.ApimException.unexpectedResponse
import uk.gov.hmrc.apihubapplications.models.exception.{ApiNotFoundException, ApimException, EmailException, IntegrationCatalogueException}
import uk.gov.hmrc.apihubapplications.models.requests.DeploymentStatus.{Deployed, NotDeployed, Unknown}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.testhelpers.ApiDetailGenerators
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, Instant, ZoneOffset}
import scala.concurrent.Future

class DeploymentsServiceSpec
  extends AsyncFreeSpec
    with Matchers
    with MockitoSugar
    with TableDrivenPropertyChecks
    with ApiDetailGenerators
    with EitherValues {

  import DeploymentsServiceSpec._

  "deployToSecondary" - {
    "must pass the request to APIM and return the response" in {
      val fixture = buildFixture()

      when(fixture.apimConnector.deployToSecondary(any)(any)).thenReturn(Future.successful(Right(deploymentsResponse)))
      when(fixture.integrationCatalogueConnector.linkApiToTeam(any)(any)).thenReturn(Future.successful(Right(())))

      fixture.deploymentsService.deployToSecondary(deploymentsRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Right(deploymentsResponse)
          verify(fixture.apimConnector).deployToSecondary(eqTo(deploymentsRequest))(any)
          succeed
      }
    }

    "must link the API and Team in Integration Catalogue" in {
      val fixture = buildFixture()
      val apiTeam = ApiTeam(deploymentsResponse.id, deploymentsRequest.teamId)

      when(fixture.apimConnector.deployToSecondary(any)(any)).thenReturn(Future.successful(Right(deploymentsResponse)))
      when(fixture.integrationCatalogueConnector.linkApiToTeam(any)(any)).thenReturn(Future.successful(Right(())))

      fixture.deploymentsService.deployToSecondary(deploymentsRequest)(HeaderCarrier()).map {
        _ =>
          verify(fixture.integrationCatalogueConnector).linkApiToTeam(eqTo(apiTeam))(any)
          succeed
      }
    }

    "must return any failure from APIM" in {
      val fixture = buildFixture()

      when(fixture.apimConnector.deployToSecondary(any)(any)).thenReturn(Future.successful(Left(ApimException.unexpectedResponse(BAD_REQUEST))))

      fixture.deploymentsService.deployToSecondary(deploymentsRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Left(ApimException.unexpectedResponse(BAD_REQUEST))
      }
    }

    "must return any failure from Integration Catalogue" in {
      val fixture = buildFixture()

      when(fixture.apimConnector.deployToSecondary(any)(any)).thenReturn(Future.successful(Right(deploymentsResponse)))
      when(fixture.integrationCatalogueConnector.linkApiToTeam(any)(any)).thenReturn(Future.successful(Left(IntegrationCatalogueException.unexpectedResponse(BAD_REQUEST))))

      fixture.deploymentsService.deployToSecondary(deploymentsRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Left(IntegrationCatalogueException.unexpectedResponse(BAD_REQUEST))
      }
    }
  }

  "redeployToSecondary" - {
    "must pass the request to APIM and return the response" in {
      val fixture = buildFixture()

      when(fixture.apimConnector.redeployToSecondary(any, any)(any)).thenReturn(Future.successful(Right(deploymentsResponse)))

      fixture.deploymentsService.redeployToSecondary(publisherRef, redeploymentRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Right(deploymentsResponse)
          verify(fixture.apimConnector).redeployToSecondary(eqTo(publisherRef), eqTo(redeploymentRequest))(any)
          succeed
      }
    }

    "must return any failure from APIM" in {
      val fixture = buildFixture()

      when(fixture.apimConnector.redeployToSecondary(any, any)(any)).thenReturn(Future.successful(Left(ApimException.unexpectedResponse(BAD_REQUEST))))

      fixture.deploymentsService.redeployToSecondary(publisherRef, redeploymentRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Left(ApimException.unexpectedResponse(BAD_REQUEST))
      }
    }
  }

  "getDeployments" - {
    "must pass the request to the APIM connector and return the response" in {
      val fixture = buildFixture()
      val publisherRef = "test-publisher-ref"
      val deploymentResponse = SuccessfulDeploymentResponse("test-id", "1")

      when(fixture.apimConnector.getDeployment(any, any)(any)).thenReturn(Future.successful(Right(Some(deploymentResponse))))

      fixture.deploymentsService.getDeployments(publisherRef)(HeaderCarrier()).map {
        result =>
          result mustBe Seq(Deployed(Primary, "1"), Deployed(Secondary, "1"))
          verify(fixture.apimConnector).getDeployment(eqTo(publisherRef), eqTo(Primary))(any)
          verify(fixture.apimConnector).getDeployment(eqTo(publisherRef), eqTo(Secondary))(any)
          succeed
      }
    }
    "must handle missing valid responses and return NotDeployed" in {
      val fixture = buildFixture()
      val publisherRef = "test-publisher-ref"
      val deploymentResponse = SuccessfulDeploymentResponse("test-id", "1")

      when(fixture.apimConnector.getDeployment(any, eqTo(Primary))(any)).thenReturn(Future.successful(Right(Some(deploymentResponse))))
      when(fixture.apimConnector.getDeployment(any, eqTo(Secondary))(any)).thenReturn(Future.successful(Right(None)))

      fixture.deploymentsService.getDeployments(publisherRef)(HeaderCarrier()).map {
        result =>
          result mustBe Seq(Deployed(Primary, "1"), NotDeployed(Secondary))
          verify(fixture.apimConnector).getDeployment(eqTo(publisherRef), eqTo(Primary))(any)
          verify(fixture.apimConnector).getDeployment(eqTo(publisherRef), eqTo(Secondary))(any)
          succeed
      }
    }
    "must handle response failures, return Unknown and record the failure on a metric" in {
      val fixture = buildFixture()
      val publisherRef = "test-publisher-ref"
      val deploymentResponse = SuccessfulDeploymentResponse("test-id", "1")

      when(fixture.apimConnector.getDeployment(any, eqTo(Primary))(any)).thenReturn(Future.successful(Right(Some(deploymentResponse))))
      when(fixture.apimConnector.getDeployment(any, eqTo(Secondary))(any)).thenReturn(Future.successful(Left(unexpectedResponse(500))))

      fixture.deploymentsService.getDeployments(publisherRef)(HeaderCarrier()).map {
        result =>
          result mustBe Seq(Deployed(Primary, "1"), Unknown(Secondary))
          verify(fixture.apimConnector).getDeployment(eqTo(publisherRef), eqTo(Primary))(any)
          verify(fixture.apimConnector).getDeployment(eqTo(publisherRef), eqTo(Secondary))(any)
          verify(fixture.metricsService).apimUnknownFailure()
          succeed
      }
    }
  }

  "getDeploymentDetails" - {
    "must pass the request to the APIM connector and return the response" in {
      val fixture = buildFixture()
      val publisherRef = "test-publisher-ref"

      val deploymentDetails = DeploymentDetails(
        description = Some("test-description"),
        status = Some("test-status"),
        domain = Some("test-domain"),
        subDomain = Some("test-dub-domain"),
        hods = Some(Seq("test-backend-1", "test-backend-2")),
        egressMappings = Some(Seq(EgressMapping("prefix", "egress-prefix"))),
        prefixesToRemove = Some(Seq("test-prefix-1", "test-prefix-2"))
      )

      when(fixture.apimConnector.getDeploymentDetails(eqTo(publisherRef))(any))
        .thenReturn(Future.successful(Right(deploymentDetails)))
      fixture.deploymentsService.getDeploymentDetails(publisherRef)(HeaderCarrier()).map {
        result =>
          result.value mustBe deploymentDetails
      }
    }
  }

  "promoteToProduction" - {
    "must pass the correct request to APIM and return the response" in {
      val publisherRef = "test-publisher-ref"

      val responses = Table(
        "response",
        Right(deploymentsResponse),
        Left(ApimException.serviceNotFound(publisherRef))
      )

      forAll(responses) { (response: Either[ApimException, DeploymentsResponse]) =>
        val fixture = buildFixture()

        when(fixture.apimConnector.promoteToProduction(any)(any)).thenReturn(Future.successful(response))

        fixture.deploymentsService.promoteToProduction(publisherRef)(HeaderCarrier()).map {
          actual =>
            actual mustBe response
            verify(fixture.apimConnector).promoteToProduction(eqTo(publisherRef))(any)
            succeed
        }
      }
    }
  }

  "updateApiTeam" - {
    "must pass the correct request to Integrations Catalogue and send appropriate emails and return the response" in {
      val fixture = buildFixture()
      val apiDetail = sampleApiDetail().copy(teamId = Some("team1"))
      val team1 = Team.apply("team 1", Seq(TeamMember("team1.member1")), Clock.fixed(Instant.now(), ZoneOffset.UTC))
      val team2 = Team.apply("team 2", Seq(TeamMember("team2.member1")), Clock.fixed(Instant.now(), ZoneOffset.UTC))

      when(fixture.teamsService.findById(eqTo("team1"))).thenReturn(Future.successful(Right(team1)))
      when(fixture.teamsService.findById(eqTo("team2"))).thenReturn(Future.successful(Right(team2)))
      when(fixture.integrationCatalogueConnector.findById(eqTo("apiId"))(any)).thenReturn(Future.successful(Right(apiDetail)))
      when(fixture.integrationCatalogueConnector.updateApiTeam(eqTo("apiId"), eqTo("team2"))(any)).thenReturn(Future.successful(Right(())))
      when(fixture.emailConnector.sendApiOwnershipChangedEmailToOldTeamMembers(eqTo(team1), eqTo(team2), eqTo(apiDetail))(any)).thenReturn(Future.successful(Right(())))
      when(fixture.emailConnector.sendApiOwnershipChangedEmailToNewTeamMembers(eqTo(team2), eqTo(apiDetail))(any)).thenReturn(Future.successful(Right(())))

      fixture.deploymentsService.updateApiTeam("apiId", "team2")(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(fixture.integrationCatalogueConnector).updateApiTeam(eqTo("apiId"), eqTo("team2"))(any)
          verify(fixture.emailConnector).sendApiOwnershipChangedEmailToOldTeamMembers(eqTo(team1), eqTo(team2), eqTo(apiDetail))(any)
          verify(fixture.emailConnector).sendApiOwnershipChangedEmailToNewTeamMembers(eqTo(team2), eqTo(apiDetail))(any)
          succeed
      }
    }

    "must handle no existing team" in {
      val fixture = buildFixture()
      val apiDetail = sampleApiDetail().copy(teamId = None)
      val team2 = Team.apply("team 2", Seq(TeamMember("team2.member1")), Clock.fixed(Instant.now(), ZoneOffset.UTC))

      when(fixture.teamsService.findById(eqTo("team2"))).thenReturn(Future.successful(Right(team2)))
      when(fixture.integrationCatalogueConnector.findById(eqTo("apiId"))(any)).thenReturn(Future.successful(Right(apiDetail)))
      when(fixture.integrationCatalogueConnector.updateApiTeam(eqTo("apiId"), eqTo("team2"))(any)).thenReturn(Future.successful(Right(())))
      when(fixture.emailConnector.sendApiOwnershipChangedEmailToNewTeamMembers(eqTo(team2), eqTo(apiDetail))(any)).thenReturn(Future.successful(Right(())))


      fixture.deploymentsService.updateApiTeam("apiId", "team2")(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(fixture.integrationCatalogueConnector).updateApiTeam(eqTo("apiId"), eqTo("team2"))(any)
          verify(fixture.emailConnector, times(0)).sendApiOwnershipChangedEmailToOldTeamMembers(any, any, any)(any)
          verify(fixture.emailConnector).sendApiOwnershipChangedEmailToNewTeamMembers(eqTo(team2), eqTo(apiDetail))(any)
          succeed
      }
    }

    "must handle email failure" in {
      val fixture = buildFixture()
      val apiDetail = sampleApiDetail().copy(teamId = None)
      val team2 = Team.apply("team 2", Seq(TeamMember("team2.member1")), Clock.fixed(Instant.now(), ZoneOffset.UTC))

      when(fixture.teamsService.findById(eqTo("team2"))).thenReturn(Future.successful(Right(team2)))
      when(fixture.integrationCatalogueConnector.findById(eqTo("apiId"))(any)).thenReturn(Future.successful(Right(apiDetail)))
      when(fixture.integrationCatalogueConnector.updateApiTeam(eqTo("apiId"), eqTo("team2"))(any)).thenReturn(Future.successful(Right(())))
      when(fixture.emailConnector.sendApiOwnershipChangedEmailToNewTeamMembers(eqTo(team2), eqTo(apiDetail))(any)).thenReturn(Future.successful(Left(EmailException.unexpectedResponse(500))))

      fixture.deploymentsService.updateApiTeam("apiId", "team2")(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(fixture.integrationCatalogueConnector).updateApiTeam(eqTo("apiId"), eqTo("team2"))(any)
          verify(fixture.emailConnector).sendApiOwnershipChangedEmailToNewTeamMembers(eqTo(team2), eqTo(apiDetail))(any)
          succeed
      }
    }

    "must propagate errors from call to integrationCatalogueConnector.updateApiTeam" in {
      val fixture = buildFixture()
      val apiDetail = sampleApiDetail().copy(teamId = None)
      val team2 = Team.apply("team 2", Seq(TeamMember("team2.member1")), Clock.fixed(Instant.now(), ZoneOffset.UTC))

      when(fixture.teamsService.findById(eqTo("team2"))).thenReturn(Future.successful(Right(team2)))
      when(fixture.integrationCatalogueConnector.findById(eqTo("apiId"))(any)).thenReturn(Future.successful(Right(apiDetail)))
      val apiNotFoundException = ApiNotFoundException.forId("apiId")
      when(fixture.integrationCatalogueConnector.updateApiTeam(eqTo("apiId"), eqTo("team2"))(any)).thenReturn(Future.successful(Left(apiNotFoundException)))

      fixture.deploymentsService.updateApiTeam("apiId", "team2")(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(apiNotFoundException)
          verify(fixture.integrationCatalogueConnector).updateApiTeam(eqTo("apiId"), eqTo("team2"))(any)
          verify(fixture.emailConnector, times(0)).sendApiOwnershipChangedEmailToOldTeamMembers(any,any,any)(any)
          verify(fixture.emailConnector, times(0)).sendApiOwnershipChangedEmailToNewTeamMembers(any,any)(any)
          succeed
      }
    }

    "must propagate errors from call to integrationCatalogueConnector.findById" in {
      val fixture = buildFixture()

      val apiNotFoundException = ApiNotFoundException.forId("apiId")
      when(fixture.integrationCatalogueConnector.findById(eqTo("apiId"))(any)).thenReturn(Future.successful(Left(apiNotFoundException)))

      fixture.deploymentsService.updateApiTeam("apiId", "team2")(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(apiNotFoundException)
          verify(fixture.integrationCatalogueConnector).findById(eqTo("apiId"))(any)
          verify(fixture.emailConnector, times(0)).sendApiOwnershipChangedEmailToOldTeamMembers(any,any,any)(any)
          verify(fixture.emailConnector, times(0)).sendApiOwnershipChangedEmailToNewTeamMembers(any,any)(any)
          succeed
      }
    }

    "removeApiTeam" - {
      "must pass the correct request to Integrations Catalogue and return the response" in {
        val fixture = buildFixture()
        val apiDetail = sampleApiDetail().copy(teamId = Some("team1"))
        when(fixture.integrationCatalogueConnector.findById(eqTo("apiId"))(any)).thenReturn(Future.successful(Right(apiDetail)))
        when(fixture.integrationCatalogueConnector.removeApiTeam(eqTo("apiId"))(any)).thenReturn(Future.successful(Right(())))

        fixture.deploymentsService.removeOwningTeamFromApi("apiId")(HeaderCarrier()).map {
          actual =>
            actual mustBe Right(())
            verify(fixture.integrationCatalogueConnector).removeApiTeam(eqTo("apiId"))(any)
            succeed
        }
      }

      "must propagate errors from call to integrationCatalogueConnector.removeApiTeam" in {
        val fixture = buildFixture()
        val apiDetail = sampleApiDetail().copy(teamId = None)

        val apiNotFoundException = ApiNotFoundException.forId("apiId")
        when(fixture.integrationCatalogueConnector.removeApiTeam(eqTo("apiId"))(any)).thenReturn(Future.successful(Left(apiNotFoundException)))

        fixture.deploymentsService.removeOwningTeamFromApi("apiId")(HeaderCarrier()).map {
          actual =>
            actual mustBe Left(apiNotFoundException)
            verify(fixture.integrationCatalogueConnector).removeApiTeam(eqTo("apiId"))(any)
            succeed
        }
      }
    }
  }

  private case class Fixture(
                              apimConnector: APIMConnector,
                              integrationCatalogueConnector: IntegrationCatalogueConnector,
                              deploymentsService: DeploymentsService,
                              teamsService: TeamsService,
                              emailConnector: EmailConnector,
                              metricsService: MetricsService,
                            )

  private def buildFixture(): Fixture = {
    val apimConnector = mock[APIMConnector]
    val integrationCatalogueConnector = mock[IntegrationCatalogueConnector]
    val emailConnector = mock[EmailConnector]
    val teamsService = mock[TeamsService]
    val metricsService = mock[MetricsService]
    val deploymentsService = new DeploymentsService(apimConnector, integrationCatalogueConnector, emailConnector, teamsService, metricsService)

    Fixture(apimConnector, integrationCatalogueConnector, deploymentsService, teamsService, emailConnector, metricsService)
  }

}

object DeploymentsServiceSpec {

  val deploymentsRequest: DeploymentsRequest = DeploymentsRequest("test-line-of-business", "test-name", "test-description", "test-egress", "test-team-id", "test-oas", false, "a status", "a domain", "a subdomain", Seq("a hod"), Seq.empty, None)
  val deploymentsResponse: SuccessfulDeploymentsResponse = SuccessfulDeploymentsResponse("test-id", "test-version", 42, "test-uri")

  val publisherRef = "test-publisher-ref"

  val redeploymentRequest: RedeploymentRequest = RedeploymentRequest(
    description = "test-description",
    oas = "test-oas",
    status = "test-status",
    domain = "a domain",
    subDomain = "a subdomain",
    hods = Seq("a hod"),
    prefixesToRemove = Seq("test-prefix-1", "test-prefix-2"),
    egressMappings = Some(Seq(EgressMapping("prefix", "egress-prefix")))
  )

}
