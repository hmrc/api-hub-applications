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
import org.mockito.Mockito.{clearInvocations, times, verify, verifyNoInteractions, when}
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatestplus.mockito.MockitoSugar
import play.api.http.Status.BAD_REQUEST
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, AutopublishConnector, EmailConnector, IntegrationCatalogueConnector}
import uk.gov.hmrc.apihubapplications.models.api.{ApiDetail, ApiTeam}
import uk.gov.hmrc.apihubapplications.models.apim.*
import uk.gov.hmrc.apihubapplications.models.application.TeamMember
import uk.gov.hmrc.apihubapplications.models.exception.ApimException.unexpectedResponse
import uk.gov.hmrc.apihubapplications.models.exception.{ApiNotFoundException, ApimException, AutopublishException, EmailException, IntegrationCatalogueException}
import uk.gov.hmrc.apihubapplications.models.requests.DeploymentStatus.{Deployed, NotDeployed, Unknown}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.testhelpers.{ApiDetailGenerators, FakeHipEnvironments}
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, Instant, LocalDateTime, ZoneId, ZoneOffset}
import scala.concurrent.Future

class DeploymentsServiceSpec
  extends AsyncFreeSpec
    with Matchers
    with MockitoSugar
    with TableDrivenPropertyChecks
    with ApiDetailGenerators
    with EitherValues {

  import DeploymentsServiceSpec._

  "createApi" - {
    "must pass the request to APIM and return the response" in {
      val fixture = buildFixture()

      when(fixture.apimConnector.createApi(any, any)(any)).thenReturn(Future.successful(Right(deploymentsResponse)))
      when(fixture.integrationCatalogueConnector.linkApiToTeam(any)(any)).thenReturn(Future.successful(Right(())))

      fixture.deploymentsService.createApi(deploymentsRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Right(deploymentsResponse)
          verify(fixture.apimConnector).createApi(eqTo(deploymentsRequest), eqTo(FakeHipEnvironments.deployTo))(any)
          succeed
      }
    }

    "must link the API and Team in Integration Catalogue" in {
      val fixture = buildFixture()
      val apiTeam = ApiTeam(deploymentsResponse.id, deploymentsRequest.teamId)

      when(fixture.apimConnector.createApi(any, any)(any)).thenReturn(Future.successful(Right(deploymentsResponse)))
      when(fixture.integrationCatalogueConnector.linkApiToTeam(any)(any)).thenReturn(Future.successful(Right(())))

      fixture.deploymentsService.createApi(deploymentsRequest)(HeaderCarrier()).map {
        _ =>
          verify(fixture.integrationCatalogueConnector).linkApiToTeam(eqTo(apiTeam))(any)
          succeed
      }
    }

    "must return any failure from APIM" in {
      val fixture = buildFixture()

      when(fixture.apimConnector.createApi(any, any)(any)).thenReturn(Future.successful(Left(ApimException.unexpectedResponse(BAD_REQUEST))))

      fixture.deploymentsService.createApi(deploymentsRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Left(ApimException.unexpectedResponse(BAD_REQUEST))
      }
    }

    "must return any failure from Integration Catalogue" in {
      val fixture = buildFixture()

      when(fixture.apimConnector.createApi(any, any)(any)).thenReturn(Future.successful(Right(deploymentsResponse)))
      when(fixture.integrationCatalogueConnector.linkApiToTeam(any)(any)).thenReturn(Future.successful(Left(IntegrationCatalogueException.unexpectedResponse(BAD_REQUEST))))

      fixture.deploymentsService.createApi(deploymentsRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Left(IntegrationCatalogueException.unexpectedResponse(BAD_REQUEST))
      }
    }
  }

  "updateApi" - {
    "must pass the request to APIM and return the response" in {
      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findByPublisherRef(any)(any)).thenReturn(Future.successful(Right(apiDetail)))
      when(fixture.apimConnector.updateApi(any, any, any)(any)).thenReturn(Future.successful(Right(deploymentsResponse)))
      when(fixture.eventService.update(any, any, any, any, any, any, any)).thenReturn(Future.successful(()))

      fixture.deploymentsService.updateApi(publisherRef, redeploymentRequest, userEmail)(HeaderCarrier()).map {
        result =>
          result mustBe Right(deploymentsResponse)

          verify(fixture.integrationCatalogueConnector).findByPublisherRef(eqTo(publisherRef))(any)

          verify(fixture.apimConnector).updateApi(
            publisherReference = eqTo(publisherRef),
            request = eqTo(redeploymentRequest),
            hipEnvironment = eqTo(FakeHipEnvironments.deployTo)
          )(any)

          verify(fixture.eventService).update(
            apiId = eqTo(apiDetail.id),
            hipEnvironment = eqTo(FakeHipEnvironments.deployTo),
            oasVersion = eqTo(oasVersion),
            request = eqTo(redeploymentRequest),
            response = eqTo(deploymentsResponse),
            userEmail = eqTo(userEmail),
            timestamp = eqTo(LocalDateTime.now(clock))
          )
          succeed
      }
    }

    "must return any failure from APIM" in {
      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findByPublisherRef(any)(any)).thenReturn(Future.successful(Right(apiDetail)))
      when(fixture.apimConnector.updateApi(any, any, any)(any)).thenReturn(Future.successful(Left(ApimException.unexpectedResponse(BAD_REQUEST))))

      fixture.deploymentsService.updateApi(publisherRef, redeploymentRequest, userEmail)(HeaderCarrier()).map {
        result =>
          verifyNoInteractions(fixture.eventService)
          result mustBe Left(ApimException.unexpectedResponse(BAD_REQUEST))
      }
    }
  }

  "getDeployments" - {
    "must pass the request to the APIM connector and return the response" in {
      val fixture = buildFixture()
      val publisherRef = "test-publisher-ref"
      val deploymentResponse = SuccessfulDeploymentResponse("test-id", Some(Instant.now()), Some("test-deployment-version"), "1", Some("test-build-version"))

      when(fixture.apimConnector.getDeployment(any, any)(any)).thenReturn(Future.successful(Right(Some(deploymentResponse))))

      fixture.deploymentsService.getDeployments(publisherRef)(HeaderCarrier()).map {
        result =>
          result mustBe Seq(Deployed(FakeHipEnvironments.productionEnvironment.id, "1"), Deployed(FakeHipEnvironments.preProductionEnvironment.id, "1"), Deployed(FakeHipEnvironments.testEnvironment.id, "1"))
          verify(fixture.apimConnector).getDeployment(eqTo(publisherRef), eqTo(FakeHipEnvironments.productionEnvironment))(any)
          verify(fixture.apimConnector).getDeployment(eqTo(publisherRef), eqTo(FakeHipEnvironments.testEnvironment))(any)
          succeed
      }
    }
    "must handle missing valid responses and return NotDeployed" in {
      val fixture = buildFixture()
      val publisherRef = "test-publisher-ref"
      val deploymentResponse = SuccessfulDeploymentResponse("test-id", Some(Instant.now()), Some("test-deployment-version"), "1", Some("test-build-version"))

      when(fixture.apimConnector.getDeployment(any, eqTo(FakeHipEnvironments.productionEnvironment))(any)).thenReturn(Future.successful(Right(Some(deploymentResponse))))
      when(fixture.apimConnector.getDeployment(any, eqTo(FakeHipEnvironments.preProductionEnvironment))(any)).thenReturn(Future.successful(Right(Some(deploymentResponse))))
      when(fixture.apimConnector.getDeployment(any, eqTo(FakeHipEnvironments.testEnvironment))(any)).thenReturn(Future.successful(Right(None)))

      fixture.deploymentsService.getDeployments(publisherRef)(HeaderCarrier()).map {
        result =>
          result mustBe Seq(Deployed(FakeHipEnvironments.productionEnvironment.id, "1"), Deployed(FakeHipEnvironments.preProductionEnvironment.id, "1"), NotDeployed(FakeHipEnvironments.testEnvironment.id))
          verify(fixture.apimConnector).getDeployment(eqTo(publisherRef), eqTo(FakeHipEnvironments.productionEnvironment))(any)
          verify(fixture.apimConnector).getDeployment(eqTo(publisherRef), eqTo(FakeHipEnvironments.testEnvironment))(any)
          succeed
      }
    }
    "must handle response failures, return Unknown and record the failure on a metric" in {
      val fixture = buildFixture()
      val publisherRef = "test-publisher-ref"
      val deploymentResponse = SuccessfulDeploymentResponse("test-id", Some(Instant.now()), Some("test-deployment-version"), "1", Some("test-build-version"))

      when(fixture.apimConnector.getDeployment(any, eqTo(FakeHipEnvironments.productionEnvironment))(any)).thenReturn(Future.successful(Right(Some(deploymentResponse))))
      when(fixture.apimConnector.getDeployment(any, eqTo(FakeHipEnvironments.preProductionEnvironment))(any)).thenReturn(Future.successful(Right(Some(deploymentResponse))))
      when(fixture.apimConnector.getDeployment(any, eqTo(FakeHipEnvironments.testEnvironment))(any)).thenReturn(Future.successful(Left(unexpectedResponse(500))))

      fixture.deploymentsService.getDeployments(publisherRef)(HeaderCarrier()).map {
        result =>
          result mustBe Seq(Deployed(FakeHipEnvironments.productionEnvironment.id, "1"), Deployed(FakeHipEnvironments.preProductionEnvironment.id, "1"), Unknown(FakeHipEnvironments.testEnvironment.id))
          verify(fixture.apimConnector).getDeployment(eqTo(publisherRef), eqTo(FakeHipEnvironments.productionEnvironment))(any)
          verify(fixture.apimConnector).getDeployment(eqTo(publisherRef), eqTo(FakeHipEnvironments.testEnvironment))(any)
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
        prefixesToRemove = Seq("test-prefix-1", "test-prefix-2"),
        egress = Some("test-egress")
      )

      when(fixture.apimConnector.getDeploymentDetails(eqTo(publisherRef), eqTo(FakeHipEnvironments.deployTo))(any))
        .thenReturn(Future.successful(Right(deploymentDetails)))
      fixture.deploymentsService.getDeploymentDetails(publisherRef)(HeaderCarrier()).map {
        result =>
          result.value mustBe deploymentDetails
      }
    }
  }

  "promoteApi" - {
    "must pass the correct request to APIM, return the response, and log the event" in {
      val publisherRef = "test-publisher-ref"
      val statusResponse = SuccessfulDeploymentResponse(apiDetail.publisherReference, None, None, oasVersion, None)
      val egress = "test-egress"

      val responses = Table(
        "response",
        Right(deploymentsResponse),
        Left(ApimException.serviceNotFound(publisherRef))
      )

      forAll(responses) { (response: Either[ApimException, DeploymentsResponse]) =>
        val fixture = buildFixture()

        when(fixture.integrationCatalogueConnector.findByPublisherRef(eqTo(apiDetail.publisherReference))(any))
          .thenReturn(Future.successful(Right(apiDetail)))
        when(fixture.apimConnector.getDeployment(eqTo(apiDetail.publisherReference), eqTo(FakeHipEnvironments.testEnvironment))(any))
          .thenReturn(Future.successful(Right(Some(statusResponse))))
        when(fixture.apimConnector.promoteAPI(any, any, any, any)(any)).thenReturn(Future.successful(response))
        when(fixture.eventService.promote(any, any, any, any ,any, any, any, any)).thenReturn(Future.successful(()))

        fixture.deploymentsService.promoteAPI(publisherRef, FakeHipEnvironments.testEnvironment, FakeHipEnvironments.productionEnvironment, egress, userEmail)(HeaderCarrier()).map {
          actual =>
            actual mustBe response

            verify(fixture.apimConnector).promoteAPI(
              eqTo(publisherRef),
              eqTo(FakeHipEnvironments.testEnvironment),
              eqTo(FakeHipEnvironments.productionEnvironment),
              eqTo(egress)
            )(any)

            response match {
              case Right(success: SuccessfulDeploymentsResponse) =>
                verify(fixture.eventService).promote(
                  apiId = eqTo(apiDetail.id),
                  fromEnvironment = eqTo(FakeHipEnvironments.testEnvironment),
                  toEnvironment = eqTo(FakeHipEnvironments.productionEnvironment),
                  oasVersion = eqTo(oasVersion),
                  egress = eqTo(egress),
                  response = eqTo(success),
                  userEmail = eqTo(userEmail),
                  timestamp = eqTo(LocalDateTime.now(clock))
                )
              case _ =>
                  verifyNoInteractions(fixture.eventService)
            }

            succeed
        }
      }
    }
  }

  "updateApiTeam" - {
    "must pass the correct request to Integrations Catalogue and send appropriate emails and return the response" in {
      val fixture = buildFixture()
      val apiDetail = sampleApiDetail().copy(teamId = Some("team1"))
      val team1 = Team.apply("team 1", Seq(TeamMember("team1.member1")), clock = Clock.fixed(Instant.now(), ZoneOffset.UTC))
      val team2 = Team.apply("team 2", Seq(TeamMember("team2.member1")), clock = Clock.fixed(Instant.now(), ZoneOffset.UTC))

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
      val team2 = Team.apply("team 2", Seq(TeamMember("team2.member1")), clock = Clock.fixed(Instant.now(), ZoneOffset.UTC))

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
      val team2 = Team.apply("team 2", Seq(TeamMember("team2.member1")), clock = Clock.fixed(Instant.now(), ZoneOffset.UTC))

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
      val team2 = Team.apply("team 2", Seq(TeamMember("team2.member1")), clock = Clock.fixed(Instant.now(), ZoneOffset.UTC))

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

  "forcePublish" - {
    "must return the response from the connector" in {
      val fixture = buildFixture()

      val responses = Table(
        "response",
        Right(()),
        Left(AutopublishException.deploymentNotFound(publisherRef))
      )

      forAll(responses) {(response: Either[AutopublishException, Unit]) =>
        when(fixture.autopublishConnector.forcePublish(eqTo(publisherRef))(any))
          .thenReturn(Future.successful(response))

        fixture.deploymentsService.forcePublish(publisherRef)(HeaderCarrier()).map(
          result =>
            result mustBe response
        )
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
    autopublishConnector: AutopublishConnector,
    eventService: ApiEventService
  )

  private def buildFixture(): Fixture = {
    val apimConnector = mock[APIMConnector]
    val integrationCatalogueConnector = mock[IntegrationCatalogueConnector]
    val emailConnector = mock[EmailConnector]
    val teamsService = mock[TeamsService]
    val metricsService = mock[MetricsService]
    val autopublishConnector = mock[AutopublishConnector]
    val eventService = mock[ApiEventService]
    val deploymentsService = new DeploymentsService(apimConnector, integrationCatalogueConnector, emailConnector, teamsService, metricsService, FakeHipEnvironments, autopublishConnector, eventService, clock)

    Fixture(apimConnector, integrationCatalogueConnector, deploymentsService, teamsService, emailConnector, metricsService, autopublishConnector, eventService)
  }

}

object DeploymentsServiceSpec extends ApiDetailGenerators {

  val deploymentsRequest: DeploymentsRequest = DeploymentsRequest("test-line-of-business", "test-name", "test-description", Some("test-egress"), "test-team-id", "test-oas", false, "a status", "a domain", "a subdomain", Seq("a hod"), Seq.empty, None, "test-base-path")
  val deploymentsResponse: SuccessfulDeploymentsResponse = SuccessfulDeploymentsResponse("test-id", "test-version", 42, "test-uri")

  val publisherRef = "test-publisher-ref"
  val userEmail = "test-user-email"
  val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
  val apiDetail: ApiDetail = sampleApiDetail().copy(publisherReference = publisherRef)
  val oasVersion = "1.0.1"

  val oas: String =
    s"""
      |openapi: 3.0.3
      |info:
      |  version: $oasVersion
      |""".stripMargin

  val redeploymentRequest: RedeploymentRequest = RedeploymentRequest(
    description = "test-description",
    oas = oas,
    status = "test-status",
    domain = "a domain",
    subDomain = "a subdomain",
    hods = Seq("a hod"),
    prefixesToRemove = Seq("test-prefix-1", "test-prefix-2"),
    egressMappings = Some(Seq(EgressMapping("prefix", "egress-prefix"))),
    egress = Some("test-egress"),
    basePath = "test-base-path"
  )

}
