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

package uk.gov.hmrc.apihubapplications.models.api

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.models.api.ApiDetailLenses._
import uk.gov.hmrc.apihubapplications.models.application.ApiLenses._
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses._
import uk.gov.hmrc.apihubapplications.models.application.{Api, Application, Creator, Endpoint => ApplicationEndpoint}

class ApiDetailLensesSpec extends AnyFreeSpec with Matchers {

  import ApiDetailLensesSpec._

  "getRequiredScopeNames" - {
    "must return scopes for the correct API, path and method" in {
      val apiDetail1 = baseApiDetail(apiId1)
        .addEndpoint(buildEndpoint(1, 1, 1))
        .addEndpoint(buildEndpoint(2, 2, 2))
        .addEndpoint(buildEndpoint(3, 1, 1))

      val apiDetail2 = baseApiDetail(apiId2)
        .addEndpoint(buildEndpoint(2, 2, 2))

      val api1 = buildApi(apiDetail1.id, apiDetail1.title)
        .addEndpoint(buildApiEndpoint(1, 1))
        .addEndpoint(buildApiEndpoint(2, 2))

      val api2 = buildApi(apiDetail2.id, apiDetail2.title)
        .addEndpoint(buildApiEndpoint(2, 1))

      val application = baseApplication
        .addApi(api1)
        .addApi(api2)

      val expected = Set(
        scopeName(1, 1, 1),
        scopeName(2, 2, 1),
        scopeName(2, 2, 2)
      )

      apiDetail1.getRequiredScopeNames(application) mustBe expected
    }
  }

}

object ApiDetailLensesSpec {


  private val apiId1: String = "test-api-id-1"
  private val apiId2: String = "test-api-id-2"

  def baseApiDetail(id: String): ApiDetail = ApiDetail(id, "test-publisher-ref", "test-title", "test-description", "test-platform", "test-version", Seq.empty, None, "test-oas", Live, None, None, None, Seq.empty)
  val baseApplication: Application = Application(Some("test-id"), "test-name", Creator("test-email"), Seq.empty)

  def buildEndpoint(index: Int, methods: Int, scopes: Int): Endpoint = {
    Endpoint(
      path = pathName(index),
      methods = (1 to methods).map(
        method =>
          EndpointMethod(
            httpMethod =  methodName(index, method),
            summary = None,
            description = None,
            scopes = (1 to scopes).map(
              scope =>
                scopeName(index, method, scope)
            )
          )
      )
    )
  }

  def pathName(index: Int): String = {
    s"test-path-$index"
  }

  def methodName(index: Int, method: Int): String = {
    s"test-method-$index-$method"
  }

  def scopeName(index: Int, method: Int, scope: Int): String = {
    s"test-scope-$index-$method-$scope"
  }

  def buildApi(apiId: String, apiTitle: String): Api = {
    Api(
      id = apiId,
      title = apiTitle,
      endpoints = Seq.empty
    )
  }

  def buildApiEndpoint(index: Int, method: Int): ApplicationEndpoint = {
    ApplicationEndpoint(
      httpMethod = methodName(index, method),
      path = pathName(index)
    )
  }

}
