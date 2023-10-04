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

package uk.gov.hmrc.apihubapplications.models.applications

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.models.application.{NewScope, Primary, Secondary}
import uk.gov.hmrc.apihubapplications.models.application.NewScope.implicits.NewScopesLensOps

class NewScopeSpec extends AnyFreeSpec with Matchers {

  "NewScopesLensOps.hasPrimaryEnvironment" - {
    "must return true when a new scope specifies the primary environment" in {
      val newScopes = Seq(
        NewScope("test-scope-1", Seq.empty),
        NewScope("test-scope-2", Seq(Secondary)),
        NewScope("test-scope-3", Seq(Primary, Secondary))
      )

      val actual = new NewScopesLensOps(newScopes).hasPrimaryEnvironment
      actual mustBe true
    }

    "must return false when a new scope does not specify the primary environment" in {
      val newScopes = Seq(
        NewScope("test-scope-1", Seq.empty),
        NewScope("test-scope-2", Seq(Secondary)),
        NewScope("test-scope-3", Seq(Secondary))
      )

      val actual = new NewScopesLensOps(newScopes).hasPrimaryEnvironment
      actual mustBe false
    }
  }

  "NewScopesLensOps.hasSecondaryEnvironment" - {
    "must return true when a new scope specifies the secondary environment" in {
      val newScopes = Seq(
        NewScope("test-scope-1", Seq.empty),
        NewScope("test-scope-2", Seq(Primary)),
        NewScope("test-scope-3", Seq(Primary, Secondary))
      )

      val actual = new NewScopesLensOps(newScopes).hasSecondaryEnvironment
      actual mustBe true
    }

    "must return false when a new scope does not specify the secondary environment" in {
      val newScopes = Seq(
        NewScope("test-scope-1", Seq.empty),
        NewScope("test-scope-2", Seq(Primary)),
        NewScope("test-scope-3", Seq(Primary))
      )

      val actual = new NewScopesLensOps(newScopes).hasSecondaryEnvironment
      actual mustBe false
    }
  }

}
