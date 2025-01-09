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

package uk.gov.hmrc.apihubapplications.config

import com.typesafe.config.ConfigFactory
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration

class HipEnvironmentsSpec  extends AsyncFreeSpec with Matchers with MockitoSugar {

  "HipEnvironments" - {
    val hipEnvironments = new HipEnvironmentsImpl(Configuration(ConfigFactory.parseString(
      s"""
         |hipEnvironments = {
         |    test = {
         |        id = "test",
         |        rank = 2,
         |        isProductionLike = false,
         |        apimUrl = "http://localhost:15027/apim-proxy/api-hub-apim-stubs"
         |        clientId = "apim-stub-client-id",
         |        secret = "apim-stub-secret",
         |        useProxy = false
         |    },
         |    production = {
         |        id = "production",
         |        rank = 1,
         |        isProductionLike = true,
         |        apimUrl = "http://localhost:15026/api-hub-apim-stubs"
         |        clientId = "apim-stub-client-id",
         |        secret = "apim-stub-secret",
         |        useProxy = false
         |    }
         |}
         |""".stripMargin)))
    "must have its environments in the right order" in {
      hipEnvironments.environments.map(_.id) must contain theSameElementsInOrderAs  Seq("production", "test")
    }
    "must return the correct production environment" in {
      hipEnvironments.productionEnvironment.id mustBe "production"
    }
    "must return the correct deployment environment" in {
      hipEnvironments.deploymentEnvironment.id mustBe "test"
    }
  }

}
