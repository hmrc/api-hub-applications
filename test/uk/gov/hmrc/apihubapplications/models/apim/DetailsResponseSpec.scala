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

package uk.gov.hmrc.apihubapplications.models.apim

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks.forAll
import org.scalatest.prop.Tables.Table
import uk.gov.hmrc.apihubapplications.models.apim.DetailsResponseSpec.buildDetailsResponse

class DetailsResponseSpec extends AnyFreeSpec with Matchers {
  "DetailsResponse.toDeploymentDetails" - {

    "must correctly handle prefixesToRemove values" in {
      forAll(Table(
        ("prefixesToRemove", "Expected DeploymentDetails value"),
        (Some(Seq("prefix")), Seq("prefix")),
        (Some(Seq.empty), Seq.empty),
        (None, Seq.empty),
      )) { (prefixesToRemove, expectedDeploymentDetailsValue) =>
        val response = buildDetailsResponse(prefixesToRemove)
        val deploymentDetails = response.toDeploymentDetails

        deploymentDetails.prefixesToRemove mustBe expectedDeploymentDetailsValue
      }
    }
  }
}

object DetailsResponseSpec{
  def buildDetailsResponse(prefixesToRemove: Option[Seq[String]]): DetailsResponse = {
    DetailsResponse(
      description = "test-description",
      status = "test-status",
      domain = "test-domain",
      subdomain = "test-subdomain",
      backends = Seq("test-backend"),
      egressMappings = None,
      prefixesToRemove = prefixesToRemove
    )
  }
}