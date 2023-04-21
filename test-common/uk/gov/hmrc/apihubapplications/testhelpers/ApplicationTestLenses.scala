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

package uk.gov.hmrc.apihubapplications.testhelpers

import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application._

object ApplicationTestLenses {
  implicit class ApplicationTestLensOps(application: Application) {
    val pendingScopeName = "my-pending-scope"
    val approvedScopeName = "my-approved-scope"
    val clientId = "fake-client-id"
    def withEmptyScopes: Application = {
      application
        .setPrimaryScopes(Seq.empty)
        .setSecondaryScopes(Seq.empty)
        .setDevScopes(Seq.empty).setTestScopes(Seq.empty).setPreProdScopes(Seq.empty).setProdScopes(Seq.empty)
    }

    def withTestPendingScopes: Application = {
      application.addTestScope(Scope(pendingScopeName, Pending))
    }

    def withProdPendingScopes: Application = {
      application.addProdScope(Scope(pendingScopeName, Pending))
    }

    def withProdApprovedScopes: Application = {
      application.addProdScope(Scope(approvedScopeName, Approved))
    }

    def withPrimaryCredentialClientIdOnly: Application = {
      application.setPrimaryCredentials(Seq(Credential(clientId,None,None)))
    }
  }

}
