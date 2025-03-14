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

package uk.gov.hmrc.apihubapplications.controllers

import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{reset, verify, verifyNoInteractions, when}
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.apihubapplications.config.HipEnvironments
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, IdmsConnector}
import uk.gov.hmrc.apihubapplications.controllers.actions.{FakeIdentifierAction, IdentifierAction}
import uk.gov.hmrc.apihubapplications.models.api.EgressGateway
import uk.gov.hmrc.apihubapplications.models.apim.{ApiDeployment, DeploymentDetails, EgressMapping, StatusResponse, SuccessfulDeploymentResponse}
import uk.gov.hmrc.apihubapplications.models.exception.{ApimException, IdmsException}
import uk.gov.hmrc.apihubapplications.models.idms.ClientScope
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments

import java.time.Instant
import scala.concurrent.Future

class ApimControllerSpec
  extends AnyFreeSpec
    with Matchers
    with MockitoSugar
    with OptionValues {

  import ApimControllerSpec.*

  "getDeployments" - {
    "must return the deployments for an environment" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment

      val apiDeployments = Seq(
        ApiDeployment("test-id-1", Some(Instant.now())),
        ApiDeployment("test-id-2", None)
      )

      when(fixture.apimConnector.getDeployments(eqTo(environment))(any)).thenReturn(Future.successful(Right(apiDeployments)))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.getDeployments(environment.id))
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(apiDeployments)
      }
    }

    "must return BadGateway for any unexpected response" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment

      when(fixture.apimConnector.getDeployments(any)(any))
        .thenReturn(Future.successful(Left(ApimException.unexpectedResponse(INTERNAL_SERVER_ERROR))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.getDeployments(environment.id))
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_GATEWAY
      }
    }
  }

  "getDeployment" - {
    "must return the deployment when it exists" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment

      val deployment = SuccessfulDeploymentResponse(
        id = "test-id",
        deploymentTimestamp = Some(Instant.now()),
        deploymentVersion = Some("test-deployment-version"),
        oasVersion = "test-oas-version",
        buildVersion = Some("test-build-version")
      )

      when(fixture.apimConnector.getDeployment(eqTo(deployment.id), eqTo(environment))(any))
        .thenReturn(Future.successful(Right(Some(deployment))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.getDeployment(environment.id, deployment.id))
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(deployment)
      }
    }

    "must return Not Found when the deployment does not exist" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment

      when(fixture.apimConnector.getDeployment(any, any)(any))
        .thenReturn(Future.successful(Right(None)))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.getDeployment(environment.id, "test-id"))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must return BadGateway for any unexpected response" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment

      when(fixture.apimConnector.getDeployment(any, any)(any))
        .thenReturn(Future.successful(Left(ApimException.unexpectedResponse(INTERNAL_SERVER_ERROR))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.getDeployment(environment.id, "test-id"))
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_GATEWAY
      }
    }
  }

  "getOpenApiSpecification" - {
    "must return the OAS when it exists" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment
      val publisherRef = "test-publisher-ref"
      val oas = "test-oas"

      when(fixture.apimConnector.getOpenApiSpecification(eqTo(publisherRef), eqTo(environment)) (any))
        .thenReturn(Future.successful(Right(oas)))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.getOpenApiSpecification(environment.id, publisherRef))
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsString(result) mustBe oas
      }
    }

    "must return Not Found when the OAS does not exist" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment
      val publisherRef = "test-publisher-ref"

      when(fixture.apimConnector.getOpenApiSpecification(any, any)(any))
        .thenReturn(Future.successful(Left(ApimException.serviceNotFound(publisherRef))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.getOpenApiSpecification(environment.id, publisherRef))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must return BadGateway for any unexpected response" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment
      val publisherRef = "test-publisher-ref"

      when(fixture.apimConnector.getOpenApiSpecification(any, any)(any))
        .thenReturn(Future.successful(Left(ApimException.unexpectedResponse(INTERNAL_SERVER_ERROR))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.getOpenApiSpecification(environment.id, publisherRef))
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_GATEWAY
      }
    }
  }

  "getDeploymentDetails" - {
    "must return the deployment details when they exist" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment
      val publisherRef = "test-publisher-ref"

      val deploymentDetails = DeploymentDetails(
        description = Some("test-description"),
        status = Some("test-status"),
        domain = Some("test-domain"),
        subDomain = Some("test-sub-domain"),
        hods = Some(Seq("test-hod")),
        egressMappings = Some(Seq(EgressMapping(prefix = "test-prefix", egressPrefix = "test-egress-prefix"))),
        prefixesToRemove = Seq("test-prefix-to-remove"),
        egress = Some("test-egress")
      )

      when(fixture.apimConnector.getDeploymentDetails(eqTo(publisherRef), eqTo(environment))(any))
        .thenReturn(Future.successful(Right(deploymentDetails)))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.getDeploymentDetails(environment.id, publisherRef))
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(deploymentDetails)
      }
    }

    "must return Not Found when the deployment details do not exist" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment
      val publisherRef = "test-publisher-ref"

      when(fixture.apimConnector.getDeploymentDetails(any, any)(any))
        .thenReturn(Future.successful(Left(ApimException.serviceNotFound(publisherRef))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.getDeploymentDetails(environment.id, publisherRef))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must return BadGateway for any unexpected response" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment
      val publisherRef = "test-publisher-ref"

      when(fixture.apimConnector.getDeploymentDetails(any, any)(any))
        .thenReturn(Future.successful(Left(ApimException.unexpectedResponse(INTERNAL_SERVER_ERROR))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.getDeploymentDetails(environment.id, publisherRef))
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_GATEWAY
      }
    }
  }

  "getDeploymentStatus" - {
    "must return the deployment status when the deployment exists" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment
      val publisherRef = "test-publisher-ref"
      val mergeRequestIid = "test-merge-request-iid"
      val version = "test-version"

      val statusResponse = StatusResponse(
        status = "test-status",
        message = Some("test-message"),
        health = Some("test-health")
      )

      when(fixture.apimConnector.getDeploymentStatus(eqTo(publisherRef), eqTo(mergeRequestIid), eqTo(version), eqTo(environment))(any))
        .thenReturn(Future.successful(Right(statusResponse)))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.getDeploymentStatus(environment.id, publisherRef, mergeRequestIid, version))
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(statusResponse)
      }
    }

    "must return Not Found when the deployment does not exist" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment
      val publisherRef = "test-publisher-ref"
      val mergeRequestIid = "test-merge-request-iid"
      val version = "test-version"

      when(fixture.apimConnector.getDeploymentStatus(any, any, any, any)(any))
        .thenReturn(Future.successful(Left(ApimException.serviceNotFound(publisherRef))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.getDeploymentStatus(environment.id, publisherRef, mergeRequestIid, version))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must return BadGateway for any unexpected response" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment
      val publisherRef = "test-publisher-ref"
      val mergeRequestIid = "test-merge-request-iid"
      val version = "test-version"

      when(fixture.apimConnector.getDeploymentStatus(any, any, any, any)(any))
        .thenReturn(Future.successful(Left(ApimException.unexpectedResponse(INTERNAL_SERVER_ERROR))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.getDeploymentStatus(environment.id, publisherRef, mergeRequestIid, version))
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_GATEWAY
      }
    }
  }

  "listEgressGateways" - {
    "must return the egress gateways for an environment" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment

      val egressGateways = Seq(
        EgressGateway(
          id = "test-id-1",
          friendlyName = "test-friendly-name-1"
        ),
        EgressGateway(
          id = "test-id-2",
          friendlyName = "test-friendly-name-2"
        )
      )

      when(fixture.apimConnector.listEgressGateways(eqTo(environment))(any))
        .thenReturn(Future.successful(Right(egressGateways)))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.listEgressGateways(environment.id))
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(egressGateways)
      }
    }

    "must return BadGateway for any unexpected response" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment

      when(fixture.apimConnector.listEgressGateways(any)(any))
        .thenReturn(Future.successful(Left(ApimException.unexpectedResponse(INTERNAL_SERVER_ERROR))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.listEgressGateways(environment.id))
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_GATEWAY
      }
    }
  }

  "fetchClientScopes" - {
    "must return the scopes when the credential exists" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment
      val clientId = "test-client-id"

      val clientScopes = Seq(
        ClientScope("test-scope-1"),
        ClientScope("test-scope-2")
      )

      when(fixture.idmsConnector.fetchClientScopes(eqTo(environment), eqTo(clientId))(any))
        .thenReturn(Future.successful(Right(clientScopes)))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.fetchClientScopes(environment.id, clientId))
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(clientScopes)
      }
    }

    "must return Not Found when the credential does not exist" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment
      val clientId = "test-client-id"

      when(fixture.idmsConnector.fetchClientScopes(any, any)(any))
        .thenReturn(Future.successful(Left(IdmsException.clientNotFound(clientId))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.fetchClientScopes(environment.id, clientId))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must return BadGateway for any unexpected response" in {
      val fixture = buildFixture()
      val environment = FakeHipEnvironments.testEnvironment
      val clientId = "test-client-id"

      when(fixture.idmsConnector.fetchClientScopes(any, any)(any))
        .thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(INTERNAL_SERVER_ERROR))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApimController.fetchClientScopes(environment.id, clientId))
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_GATEWAY
      }
    }
  }

  private def buildFixture(): Fixture = {
    val apimConnector = mock[APIMConnector]
    val idmsConnector = mock[IdmsConnector]

    val application = new GuiceApplicationBuilder()
      .overrides(
        bind[ControllerComponents].toInstance(stubControllerComponents()),
        bind[IdentifierAction].to(classOf[FakeIdentifierAction]),
        bind[HipEnvironments].toInstance(FakeHipEnvironments),
        bind[APIMConnector].toInstance(apimConnector),
        bind[IdmsConnector].toInstance(idmsConnector)
      )
      .build()

    Fixture(apimConnector, idmsConnector, application)
  }

}

private object ApimControllerSpec {

  case class Fixture(
    apimConnector: APIMConnector,
    idmsConnector: IdmsConnector,
    application: Application
  )

}
