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

package uk.gov.hmrc.apihubapplications.models.requests

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json.Json
import uk.gov.hmrc.apihubapplications.models.application.Primary
import uk.gov.hmrc.apihubapplications.models.requests.DeploymentStatus.*
import uk.gov.hmrc.apihubapplications.models.requests.DeploymentStatus.NotDeployed

class DeploymentStatusSpec extends AnyFreeSpec with Matchers{

  "DeploymentStatus" - {
    "must serialize a deployment status into the expected json" in {
      val deploymentStatus: DeploymentStatus = NotDeployed(Primary)
      val json = Json.prettyPrint(Json.toJson[DeploymentStatus](deploymentStatus))

      json mustBe """{
                    |  "environmentName" : "primary",
                    |  "_type" : "NotDeployed"
                    |}""".stripMargin
    }
  }

}