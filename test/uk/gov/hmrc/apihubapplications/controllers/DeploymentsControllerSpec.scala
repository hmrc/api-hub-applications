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
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar.mock
import org.mockito.{ArgumentMatchers, MockitoSugar}
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.{TableDrivenPropertyChecks, TableFor2, TableFor3}
import play.api.http.Status
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{ControllerComponents, Request}
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import play.api.{Application => PlayApplication}
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, APIMConnectorImpl}
import uk.gov.hmrc.apihubapplications.controllers.DeploymentsControllerSpec.{buildFixture, failResponses, successResponses}
import uk.gov.hmrc.apihubapplications.controllers.actions.{FakeIdentifierAction, IdentifierAction}
import uk.gov.hmrc.apihubapplications.models.apim._
import uk.gov.hmrc.apihubapplications.models.application.{Primary, Secondary}
import uk.gov.hmrc.apihubapplications.models.exception.ApimException
import uk.gov.hmrc.apihubapplications.models.requests.DeploymentStatus
import uk.gov.hmrc.apihubapplications.utils.CryptoUtils

import scala.concurrent.Future

class DeploymentsControllerSpec
  extends AnyFreeSpec
    with Matchers
    with MockitoSugar
    with OptionValues
    with CryptoUtils
    with TableDrivenPropertyChecks {

  "registerApplication" - {
    "must return Accepted for a valid request with a success response from downstream" in {
      val fixture = DeploymentsControllerSpec.buildFixture()
      running(fixture.application) {
        val deployRequest = DeploymentsRequest(
          "lineOfBusiness",
          "name",
          "description",
          "egress",
          "oas"
        )

        val deployResponse = SuccessfulDeploymentsResponse("example-api-id", "v1.2.3", 666, "example-uri")
        val json = Json.toJson(deployRequest)

        val request: Request[JsValue] = FakeRequest(POST, routes.DeploymentsController.generate().url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        when(fixture.apimConnector.deployToSecondary(ArgumentMatchers.eq(deployRequest))(any()))
          .thenReturn(Future.successful(Right(deployResponse)))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe Json.toJson(deployResponse)
      }

    }

    "must return 400 Bad Request and an Invalid OAS spec when returned from downstream" in {
      val fixture = DeploymentsControllerSpec.buildFixture()
      running(fixture.application) {
        val deployRequest = DeploymentsRequest(
          "lineOfBusiness",
          "name",
          "description",
          "egress",
          "oas"
        )

        val deployResponse = InvalidOasResponse(Seq(ValidationFailure("test-type", "test-message")))
        val json = Json.toJson(deployRequest)

        val request: Request[JsValue] = FakeRequest(POST, routes.DeploymentsController.generate().url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        when(fixture.apimConnector.deployToSecondary(ArgumentMatchers.eq(deployRequest))(any()))
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

    "must return Bad request if the downstream service responds with validation errors" in {
      val fixture = DeploymentsControllerSpec.buildFixture()
      running(fixture.application) {
        val deployRequest = DeploymentsRequest(
          "lineOfBusiness",
          "name",
          "description",
          "egress",
          "oas"
        )

        val failures = Seq(
          ValidationFailure("test-type-1", "test-message-1"),
          ValidationFailure("test-type-2", "test-message-2")
        )

        val response = Right(InvalidOasResponse(failures))

        val json = Json.toJson(deployRequest)

        val request: Request[JsValue] = FakeRequest(POST, routes.DeploymentsController.generate().url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        when(fixture.apimConnector.deployToSecondary(ArgumentMatchers.eq(deployRequest))(any()))
          .thenReturn(Future.successful(response))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_REQUEST
      }

    }


    "must return 500 Internal Server Error for unexpected exceptions" in {
      val fixture = buildFixture()
      val deployRequest = DeploymentsRequest(
        "lineOfBusiness",
        "name",
        "description",
        "egress",
        "oas"
      )
      val json = Json.toJson(deployRequest)

      when(fixture.apimConnector.deployToSecondary(ArgumentMatchers.eq(deployRequest))(any()))
        .thenReturn(Future.successful(Left(ApimException.unexpectedResponse(500))))

      running(fixture.application) {
        val request: Request[JsValue] = FakeRequest(POST, routes.DeploymentsController.generate().url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        val result = route(fixture.application, request).value
        status(result) mustBe INTERNAL_SERVER_ERROR
      }
    }
  }

  "getDeploymentStatus" - {
    "must return Ok with correct response" in {
      val fixture = DeploymentsControllerSpec.buildFixture()
      running(fixture.application) {

        val publisherRef = "publisher_ref"

        forAll(successResponses) { (primaryResponse, secondaryResponse, expected) =>

          val request = FakeRequest(GET, routes.DeploymentsController.getDeploymentStatus(publisherRef).url)

          when(fixture.apimConnector.getDeployment(ArgumentMatchers.eq(publisherRef), ArgumentMatchers.eq(Primary))(any()))
            .thenReturn(Future.successful(Right(primaryResponse)))
          when(fixture.apimConnector.getDeployment(ArgumentMatchers.eq(publisherRef), ArgumentMatchers.eq(Secondary))(any()))
            .thenReturn(Future.successful(Right(secondaryResponse)))
          val result = route(fixture.application, request).value

          status(result) mustBe Status.OK
          contentAsJson(result) mustBe Json.toJson(expected)
        }
      }

    }

    "must return Bad Gateway" in {
      val fixture = DeploymentsControllerSpec.buildFixture()
      running(fixture.application) {

        val publisherRef = "publisher_ref"

        forAll(failResponses) { (primaryResponse, secondaryResponse) =>

          val request = FakeRequest(GET, routes.DeploymentsController.getDeploymentStatus(publisherRef).url)

          when(fixture.apimConnector.getDeployment(ArgumentMatchers.eq(publisherRef), ArgumentMatchers.eq(Primary))(any()))
            .thenReturn(Future.successful(primaryResponse))
          when(fixture.apimConnector.getDeployment(ArgumentMatchers.eq(publisherRef), ArgumentMatchers.eq(Secondary))(any()))
            .thenReturn(Future.successful(secondaryResponse))
          val result = route(fixture.application, request).value

          status(result) mustBe Status.BAD_GATEWAY
        }
      }
    }
  }
}

object DeploymentsControllerSpec extends TableDrivenPropertyChecks {

  implicit val materializer: Materializer = Materializer(ActorSystem())

  case class Fixture(
                      application: PlayApplication,
                      apimConnector: APIMConnector
                    )

  def buildFixture(): Fixture = {
    val apimConnector = mock[APIMConnectorImpl]

    val application = new GuiceApplicationBuilder()
      .overrides(
        bind[ControllerComponents].toInstance(Helpers.stubControllerComponents()),
        bind[APIMConnectorImpl].toInstance(apimConnector),
        bind[IdentifierAction].to(classOf[FakeIdentifierAction])
      )
      .build()

    Fixture(application, apimConnector)

  }
  val deploymentResponse = SuccessfulDeploymentResponse("publisher_ref")

  val successResponses: TableFor3[Option[SuccessfulDeploymentResponse], Option[SuccessfulDeploymentResponse], DeploymentStatus] = Table(
    ("primary", "secondary", "expected"),
    (Some(deploymentResponse), Some(deploymentResponse), DeploymentStatus(true,true)),
    (Some(deploymentResponse), None, DeploymentStatus(true,false)),
    (None, Some(deploymentResponse), DeploymentStatus(false,true)),
    (None, None, DeploymentStatus(false,false))
  )

  private val exception: ApimException = ApimException.unexpectedResponse(500)
  val failResponses: TableFor2[Either[ApimException, Option[SuccessfulDeploymentResponse]], Either[ApimException, Option[SuccessfulDeploymentResponse]]] = Table(
    ("primary", "secondary"),
    (Right(Some(deploymentResponse)), Left(exception)),
    (Left(exception), Right(Some(deploymentResponse))),
    (Left(exception), Left(exception))
  )

}
