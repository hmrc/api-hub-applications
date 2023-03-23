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

package uk.gov.hmrc.apihubapplications.controllers.actions

import org.mockito.{ArgumentMatchers, MockitoSugar}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import play.api.http.Status
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.Results.Ok
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.internalauth.client._
import uk.gov.hmrc.internalauth.client.test.{BackendAuthComponentsStub, StubBehaviour}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AuthActionSpec extends AnyFreeSpec with MockitoSugar {


  class Harness(authAction: IdentifierAction) {
    def onPageLoad(): Action[AnyContent] = authAction { _ => Results.Ok }
  }

  "Auth Action" - {

    "when the client has not set an auth header is unauthorized" - {

      "must return unauthorized" in {

        val application = new GuiceApplicationBuilder().build()

        running(application) {
          val bodyParsers = application.injector.instanceOf[BodyParsers.Default]
          val authAction = new AuthenticatedIdentifierAction(bodyParsers, application.injector.instanceOf[BackendAuthComponents])
          val controller = new Harness(authAction)
          val result = controller.onPageLoad()(FakeRequest())

          status(result) mustBe UNAUTHORIZED
        }
      }
    }

    "when the client has set an auth header is authorized" - {

      "must return ok" in {
        implicit val cc = Helpers.stubControllerComponents()
        val mockStubBehaviour = mock[StubBehaviour]
        val stubAuth = BackendAuthComponentsStub(mockStubBehaviour)

        val application = new GuiceApplicationBuilder()
          .bindings(
            bind[BackendAuthComponents].toInstance(stubAuth)
          )
          .build()

        running(application) {
          val authAction = application.injector.instanceOf[AuthenticatedIdentifierAction]

          val canAccessPredicate = Predicate.Permission(
            Resource(
              ResourceType("api-hub-applications"),
              ResourceLocation("*")
            ),
            IAAction("WRITE")
          )

          when (mockStubBehaviour.stubAuth(ArgumentMatchers.eq(Some(canAccessPredicate)), ArgumentMatchers.eq(Retrieval.EmptyRetrieval))).thenReturn(Future.unit)

          val result = authAction.invokeBlock(
            FakeRequest().withHeaders("Authorization" -> "Anything whatsoever"),
            (_: Request[AnyContent]) => {
              Future.successful(Ok)
            }
          )

          status(result) mustBe OK
        }
      }
    }
  }

  "when the client has set an auth header is authorized" - {

    "must return ok" in {
      implicit val cc = Helpers.stubControllerComponents()
      val mockStubBehaviour = mock[StubBehaviour]
      val stubAuth = BackendAuthComponentsStub(mockStubBehaviour)

      val application = new GuiceApplicationBuilder()
        .bindings(
          bind[BackendAuthComponents].toInstance(stubAuth)
        )
        .build()

      running(application) {
        val authAction = application.injector.instanceOf[AuthenticatedIdentifierAction]

        val canAccessPredicate = Predicate.Permission(
          Resource(
            ResourceType("api-hub-applications"),
            ResourceLocation("*")
          ),
          IAAction("WRITE")
        )

        when(mockStubBehaviour.stubAuth(ArgumentMatchers.eq(Some(canAccessPredicate)), ArgumentMatchers.eq(Retrieval.EmptyRetrieval))).thenReturn(Future.unit)

        val result = authAction.invokeBlock(
          FakeRequest().withHeaders("Authorization" -> "Anything whatsoever"),
          (_: Request[AnyContent]) => {
            Future.successful(Ok)
          }
        )

        status(result) mustBe OK
      }
    }
  }

  "when the client has set a valid auth header is but is unauthorized" - {

    "must return unauthorised" in {
      implicit val cc = Helpers.stubControllerComponents()
      val mockStubBehaviour = mock[StubBehaviour]
      val stubAuth = BackendAuthComponentsStub(mockStubBehaviour)

      val application = new GuiceApplicationBuilder()
        .bindings(
          bind[BackendAuthComponents].toInstance(stubAuth)
        )
        .build()

      running(application) {
        val authAction = application.injector.instanceOf[AuthenticatedIdentifierAction]

        val canAccessPredicate = Predicate.Permission(
          Resource(
            ResourceType("api-hub-applications"),
            ResourceLocation("*")
          ),
          IAAction("WRITE")
        )

        when(mockStubBehaviour.stubAuth(ArgumentMatchers.eq(Some(canAccessPredicate)), ArgumentMatchers.eq(Retrieval.EmptyRetrieval)))
          .thenReturn(Future.failed(UpstreamErrorResponse("Unauthorized", Status.UNAUTHORIZED)))

        val result = authAction.invokeBlock(
          FakeRequest().withHeaders("Authorization" -> "Anything whatsoever"),
          (_: Request[AnyContent]) => {
            Future.successful(Ok)
          }
        )

        status(result) mustBe UNAUTHORIZED
      }
    }
  }
}
