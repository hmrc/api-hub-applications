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

package uk.gov.hmrc.apihubapplications.testhelpers

import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}

object FakeHipEnvironments extends HipEnvironments {

  val primaryEnvironment: HipEnvironment = HipEnvironment(
    id = "production",
    rank = 1,
    isProductionLike = true,
    apimUrl = "http://apim.test/production",
    clientId = "test-production-client-id",
    secret = "test-production-secret",
    useProxy = false,
    apiKey = None,
    apimEnvironmentName = "production"
  )
  val secondaryEnvironment: HipEnvironment = HipEnvironment(
    id = "test",
    rank = 2,
    isProductionLike = false,
    apimUrl = "http://apim.test/test",
    clientId = "test-test-client-id",
    secret = "test-test-secret",
    useProxy = false,
    apiKey = Some("test-api-key"),
    apimEnvironmentName = "test"
  )

  override val environments: Seq[HipEnvironment] = Seq(
    primaryEnvironment,
    secondaryEnvironment
  )

  override def productionEnvironment: HipEnvironment = primaryEnvironment

  override def deploymentEnvironment: HipEnvironment = secondaryEnvironment

}
