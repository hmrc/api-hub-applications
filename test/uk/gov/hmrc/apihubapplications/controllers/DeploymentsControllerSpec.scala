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

package uk.gov.hmrc.apihubapplications.controllers

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{verify, when}
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.{TableDrivenPropertyChecks, TableFor2}
import org.scalatestplus.mockito.MockitoSugar
import play.api.http.Status
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{ControllerComponents, Request}
import play.api.test.Helpers.*
import play.api.test.{FakeRequest, Helpers}
import play.api.Application as PlayApplication
import uk.gov.hmrc.apihubapplications.controllers.actions.{FakeIdentifierAction, IdentifierAction}
import uk.gov.hmrc.apihubapplications.models.apim.*
import uk.gov.hmrc.apihubapplications.models.exception.{ApiNotFoundException, ApimException}
import uk.gov.hmrc.apihubapplications.models.requests.{DeploymentStatus, DeploymentStatuses}
import uk.gov.hmrc.apihubapplications.models.requests.DeploymentStatus.*
import uk.gov.hmrc.apihubapplications.services.DeploymentsService
import uk.gov.hmrc.apihubapplications.testhelpers.{ApiDetailGenerators, FakeHipEnvironments}
import uk.gov.hmrc.apihubapplications.utils.CryptoUtils

import scala.concurrent.Future

class DeploymentsControllerSpec
  extends AnyFreeSpec
    with Matchers
    with MockitoSugar
    with OptionValues
    with CryptoUtils
    with TableDrivenPropertyChecks
    with ApiDetailGenerators {

  import DeploymentsControllerSpec._

  "generate" - {
    "must return Ok for a valid request with a success response from downstream" in {
      val fixture = DeploymentsControllerSpec.buildFixture()
      running(fixture.application) {
        val deployRequest = DeploymentsRequest(
          "lineOfBusiness",
          "name",
          "description",
          Some("test-egress"),
          "teamId",
          "oas",
          false,
          "status",
          "a domain",
          "a subdomain",
          Seq("a hod"),
          Seq.empty,
          None
        )

        val deployResponse = SuccessfulDeploymentsResponse("example-api-id", "v1.2.3", 666, "example-uri")
        val json = Json.toJson(deployRequest)

        val request: Request[JsValue] = FakeRequest(POST, routes.DeploymentsController.generate().url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        when(fixture.deploymentsService.deployToSecondary(eqTo(deployRequest))(any()))
          .thenReturn(Future.successful(Right(deployResponse)))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe Json.toJson(deployResponse)
      }

    }

    "must return 400 Bad Request and a Failure with Errors when returned from downstream" in {
      val fixture = DeploymentsControllerSpec.buildFixture()
      running(fixture.application) {
        val deployRequest = DeploymentsRequest(
          "lineOfBusiness",
          "name",
          "description",
          Some("test-egress"),
          "teamId",
          "oas",
          false,
          "status",
          "a domain",
          "a subdomain",
          Seq("a hod"),
          Seq.empty,
          None
        )

        val errors = Seq(Error("test-type", "test-message"))
        val deployResponse = InvalidOasResponse(FailuresResponse("failure_code", "failure_reason", Some(errors)))
        val json = Json.toJson(deployRequest)

        val request: Request[JsValue] = FakeRequest(POST, routes.DeploymentsController.generate().url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        when(fixture.deploymentsService.deployToSecondary(eqTo(deployRequest))(any()))
          .thenReturn(Future.successful(Right(deployResponse)))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_REQUEST
        contentAsJson(result) mustBe Json.toJson(deployResponse)
      }
    }

    "must return 400 Bad Request and a Failure without Errors when returned from downstream" in {
      val fixture = DeploymentsControllerSpec.buildFixture()
      running(fixture.application) {
        val deployRequest = DeploymentsRequest(
          "lineOfBusiness",
          "name",
          "description",
          Some("test-egress"),
          "teamId",
          "oas",
          false,
          "status",
          "a domain",
          "a subdomain",
          Seq("a hod"),
          Seq.empty,
          None
        )

        val deployResponse = InvalidOasResponse(FailuresResponse("failure_code", "failure_reason", None))
        val json = Json.toJson(deployRequest)

        val request: Request[JsValue] = FakeRequest(POST, routes.DeploymentsController.generate().url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        when(fixture.deploymentsService.deployToSecondary(eqTo(deployRequest))(any()))
          .thenReturn(Future.successful(Right(deployResponse)))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_REQUEST
        contentAsJson(result) mustBe Json.toJson(deployResponse)
      }
    }

    "must return 400 Bad Request when the JSON is not a valid application" in {
      val fixture = buildFixture()
      running(fixture.application) {
        val request: Request[JsValue] = FakeRequest(POST, routes.DeploymentsController.generate().url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.obj())

        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_REQUEST
      }
    }

    "must return Bad request if the downstream service responds with a failure" in {
      val fixture = DeploymentsControllerSpec.buildFixture()
      running(fixture.application) {
        val deployRequest = DeploymentsRequest(
          "lineOfBusiness",
          "name",
          "description",
          Some("test-egress"),
          "teamId",
          "oas",
          false,
          "status",
          "a domain",
          "a subdomain",
          Seq("a hod"),
          Seq.empty,
          None
        )

        val response = Right(InvalidOasResponse(FailuresResponse("failure_code", "failure_reason", None)))

        val json = Json.toJson(deployRequest)

        val request: Request[JsValue] = FakeRequest(POST, routes.DeploymentsController.generate().url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        when(fixture.deploymentsService.deployToSecondary(eqTo(deployRequest))(any()))
          .thenReturn(Future.successful(response))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_REQUEST
      }
    }

    "must throw ApimException when raised downstream" in {
      val fixture = buildFixture()

      val deployRequest = DeploymentsRequest(
        "lineOfBusiness",
        "name",
        "description",
        Some("test-egress"),
        "teamId",
        "oas",
        false,
        "status",
        "a domain",
        "a subdomain",
        Seq("a hod"),
        Seq.empty,
        None
      )

      running(fixture.application) {
        val request = FakeRequest(routes.DeploymentsController.generate())
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(deployRequest))

        when(fixture.deploymentsService.deployToSecondary(any())(any()))
          .thenReturn(Future.successful(Left(ApimException.unexpectedResponse(500))))

        val result = route(fixture.application, request).value
        val e = the[ApimException] thrownBy status(result)
        e mustBe ApimException.unexpectedResponse(500)
      }

    }
  }

  "update" - {
    "must return Ok for a valid request with a success response from downstream" in {
      val fixture = buildFixture()

      running(fixture.application) {
        val request = FakeRequest(routes.DeploymentsController.update(publisherRef))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(redeploymentRequest))

        when(fixture.deploymentsService.redeployToSecondary(eqTo(publisherRef), eqTo(redeploymentRequest))(any()))
          .thenReturn(Future.successful(Right(deploymentsResponse)))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe Json.toJson(deploymentsResponse)
      }
    }

    "must return 400 Bad Request and a Failure with Errors when returned from downstream" in {
      val fixture = buildFixture()

      running(fixture.application) {
        val request = FakeRequest(routes.DeploymentsController.update(publisherRef))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(redeploymentRequest))

        when(fixture.deploymentsService.redeployToSecondary(eqTo(publisherRef), eqTo(redeploymentRequest))(any()))
          .thenReturn(Future.successful(Right(invalidOasResponse)))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_REQUEST
        contentAsJson(result) mustBe Json.toJson(invalidOasResponse)
      }
    }

    "must return 400 Bad Request when the JSON is not a valid application" in {
      val fixture = buildFixture()

      running(fixture.application) {
        val request = FakeRequest(routes.DeploymentsController.update(publisherRef))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.obj())

        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_REQUEST
      }
    }

    "must throw ApimException when raised downstream" in {
      val fixture = buildFixture()

      running(fixture.application) {
        val request = FakeRequest(routes.DeploymentsController.update(publisherRef))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(redeploymentRequest))

        when(fixture.deploymentsService.redeployToSecondary(any(), any())(any()))
          .thenReturn(Future.successful(Left(ApimException.unexpectedResponse(500))))

        val result = route(fixture.application, request).value
        val e = the[ApimException] thrownBy status(result)
        e mustBe ApimException.unexpectedResponse(500)
      }
    }

    "must return 404 Not Found when the publisher ref does not identify a service known to APIM" in {
      val fixture = buildFixture()

      running(fixture.application) {
        val request = FakeRequest(routes.DeploymentsController.update(publisherRef))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(redeploymentRequest))

        when(fixture.deploymentsService.redeployToSecondary(any(), any())(any()))
          .thenReturn(Future.successful(Left(ApimException.serviceNotFound(publisherRef))))

        val result = route(fixture.application, request).value
        status(result) mustBe NOT_FOUND
      }
    }
  }

  "getDeploymentStatus" - {
    "must return Ok with correct response" in {
      val fixture = DeploymentsControllerSpec.buildFixture()
      running(fixture.application) {

        val publisherRef = "publisher_ref"

        val request = FakeRequest(GET, routes.DeploymentsController.getDeploymentStatus(publisherRef).url)
        val response = Seq(Deployed(FakeHipEnvironments.primaryEnvironment.id, "1"), Deployed(FakeHipEnvironments.secondaryEnvironment.id, "1"))

        when(fixture.deploymentsService.getDeployments(eqTo(publisherRef))(any()))
          .thenReturn(Future.successful(response))
        val result = route(fixture.application, request).value

        status(result) mustBe Status.OK
        contentAsJson(result) mustBe Json.toJson[DeploymentStatuses](DeploymentStatuses(response))
      }

    }
  }

  "getDeploymentDetails" - {
    "must return 200 Ok and a DeploymentDetails JSON payload when returned by APIM" in {
      val fixture = buildFixture()

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

      when(fixture.deploymentsService.getDeploymentDetails(eqTo(publisherRef))(any()))
        .thenReturn(Future.successful(Right(deploymentDetails)))

      running(fixture.application) {
        val request = FakeRequest(routes.DeploymentsController.getDeploymentDetails(publisherRef))
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(deploymentDetails)
      }
    }

    "must return 404 Not Found when the service does not exist in APIM" in {
      val fixture = buildFixture()

      when(fixture.deploymentsService.getDeploymentDetails(eqTo(publisherRef))(any()))
        .thenReturn(Future.successful(Left(ApimException.serviceNotFound(publisherRef))))

      running(fixture.application) {
        val request = FakeRequest(routes.DeploymentsController.getDeploymentDetails(publisherRef))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }
  }

  "promoteToProduction" - {
    "must return 200 Ok and a SuccessfulDeploymentsResponse when APIM returns success" in {
      val fixture = buildFixture()

      when(fixture.deploymentsService.promoteToProduction(any)(any))
        .thenReturn(Future.successful(Right(deploymentsResponse)))

      running(fixture.application) {
        val request = FakeRequest(routes.DeploymentsController.promoteToProduction(publisherRef))
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(deploymentsResponse)

        verify(fixture.deploymentsService).promoteToProduction(eqTo(publisherRef))(any)
      }
    }

    "must return 400 Bad Request and an InvalidOasResponse when APIM returns failure" in {
      val fixture = buildFixture()

      when(fixture.deploymentsService.promoteToProduction(any)(any))
        .thenReturn(Future.successful(Right(invalidOasResponse)))

      running(fixture.application) {
        val request = FakeRequest(routes.DeploymentsController.promoteToProduction(publisherRef))
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.toJson(invalidOasResponse)
      }
    }

    "must return 404 Not Found when APIM cannot find the service" in {
      val fixture = buildFixture()

      when(fixture.deploymentsService.promoteToProduction(any)(any))
        .thenReturn(Future.successful(Left(ApimException.serviceNotFound(publisherRef))))

      running(fixture.application) {
        val request = FakeRequest(routes.DeploymentsController.promoteToProduction(publisherRef))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must throw ApimException when raised downstream" in {
      val fixture = buildFixture()

      when(fixture.deploymentsService.promoteToProduction(any)(any))
        .thenReturn(Future.successful(Left(ApimException.unexpectedResponse(INTERNAL_SERVER_ERROR))))

      running(fixture.application) {
        val request = FakeRequest(routes.DeploymentsController.promoteToProduction(publisherRef))
        val result = route(fixture.application, request).value

        val e = the[ApimException] thrownBy status(result)
        e mustBe ApimException.unexpectedResponse(INTERNAL_SERVER_ERROR)
      }
    }
  }


  "updateApiTeam" - {
    val apiId = "apiId"
    "must return 200 Ok" in {
      val fixture = buildFixture()

      when(fixture.deploymentsService.updateApiTeam(eqTo(apiId), eqTo("team2"))(any))
        .thenReturn(Future.successful(Right(())))

      running(fixture.application) {
        val request = FakeRequest(routes.DeploymentsController.updateApiTeam(apiId, "team2"))
        val result = route(fixture.application, request).value

        status(result) mustBe OK

        verify(fixture.deploymentsService).updateApiTeam(eqTo(apiId), eqTo("team2"))(any)
      }
    }

    "must return 404 Not found when service returns not found " in {
      val fixture = buildFixture()

      when(fixture.deploymentsService.updateApiTeam(eqTo(apiId), eqTo("team2"))(any))
        .thenReturn(Future.successful(Left(ApiNotFoundException.forId(apiId))))

      running(fixture.application) {
        val request = FakeRequest(routes.DeploymentsController.updateApiTeam(apiId, "team2"))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND

        verify(fixture.deploymentsService).updateApiTeam(eqTo(apiId), eqTo("team2"))(any)
      }
    }

  }

  "removeApiTeam" - {
    val apiId = "apiId"
    "must return 200 Ok" in {
      val fixture = buildFixture()

      when(fixture.deploymentsService.removeOwningTeamFromApi(eqTo(apiId))(any))
        .thenReturn(Future.successful(Right(())))

      running(fixture.application) {
        val request = FakeRequest(routes.DeploymentsController.removeTeam(apiId))
        val result = route(fixture.application, request).value

        status(result) mustBe NO_CONTENT

        verify(fixture.deploymentsService).removeOwningTeamFromApi(eqTo(apiId))(any)
      }
    }

    "must return 404 Not found when service returns not found " in {
      val fixture = buildFixture()

      when(fixture.deploymentsService.removeOwningTeamFromApi(eqTo(apiId))(any))
        .thenReturn(Future.successful(Left(ApiNotFoundException.forId(apiId))))

      running(fixture.application) {
        val request = FakeRequest(routes.DeploymentsController.removeTeam(apiId))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND

        verify(fixture.deploymentsService).removeOwningTeamFromApi(eqTo(apiId))(any)
      }
    }

  }

}



object DeploymentsControllerSpec extends TableDrivenPropertyChecks with MockitoSugar {

  implicit val materializer: Materializer = Materializer(ActorSystem())

  case class Fixture(
                      application: PlayApplication,
                      deploymentsService: DeploymentsService
                    )

  def buildFixture(): Fixture = {
    val deploymentsService = mock[DeploymentsService]

    val application = new GuiceApplicationBuilder()
      .overrides(
        bind[ControllerComponents].toInstance(Helpers.stubControllerComponents()),
        bind[DeploymentsService].toInstance(deploymentsService),
        bind[IdentifierAction].to(classOf[FakeIdentifierAction])
      )
      .build()

    Fixture(application, deploymentsService)
  }

  val publisherRef = "test-publisher-ref"

  val redeploymentRequest: RedeploymentRequest = RedeploymentRequest(
    description = "test-description",
    oas = "test-oas",
    status = "test-status",
    domain = "a different domain",
    subDomain = "a different subdomain",
    hods = Seq("a different hod"),
    prefixesToRemove = Seq("test-prefix-1", "test-prefix-2"),
    egressMappings = Some(Seq(EgressMapping("prefix", "egress-prefix"))),
    egress = Some("test-egress"),
  )

  val deploymentsResponse: SuccessfulDeploymentsResponse = SuccessfulDeploymentsResponse("example-api-id", "v1.2.3", 666, "example-uri")
  val deploymentResponse: SuccessfulDeploymentResponse = SuccessfulDeploymentResponse("publisher_ref", "1")

  val invalidOasResponse: InvalidOasResponse = InvalidOasResponse(
    failure = FailuresResponse(
      code = "test-code",
      reason = "test-reason",
      errors = Some(Seq(
        Error(
          `type` = "test-type",
          message = "test-message"
        )
      ))
    )
  )

  private val exception: ApimException = ApimException.unexpectedResponse(500)

  val failResponses: TableFor2[Either[ApimException, Option[SuccessfulDeploymentResponse]], Either[ApimException, Option[SuccessfulDeploymentResponse]]] = Table(
    ("primary", "secondary"),
    (Right(Some(deploymentResponse)), Left(exception)),
    (Left(exception), Right(Some(deploymentResponse))),
    (Left(exception), Left(exception))
  )

}
