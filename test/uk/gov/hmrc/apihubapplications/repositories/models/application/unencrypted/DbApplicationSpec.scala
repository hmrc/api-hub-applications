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

package uk.gov.hmrc.apihubapplications.repositories.models.application.unencrypted

import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments

import java.time.LocalDateTime

class DbApplicationSpec extends AnyFreeSpec with Matchers with OptionValues {

  import DbApplicationSpec._

  "DbApplication" - {
    "when translating from Application to DbApplication" - {
      "must remove client secrets" in {
        val productionCredential = Credential("test-client-id-1", now, Some("test-secret-1"), Some("test-fragment-1"), FakeHipEnvironments.productionEnvironment.id)
        val testCredential = Credential("test-client-id-2", now, Some("test-secret-2"), Some("test-fragment-2"), FakeHipEnvironments.testEnvironment.id)

        val productionDbCredential = DbCredential(productionCredential.clientId, Some(productionCredential.created), productionCredential.secretFragment, productionCredential.environmentId)
        val testDbCredential = DbCredential(testCredential.clientId, Some(testCredential.created), testCredential.secretFragment, testCredential.environmentId)

        val application = testApplication
          .addCredential(FakeHipEnvironments.productionEnvironment, productionCredential)
          .addCredential(FakeHipEnvironments.testEnvironment, testCredential)

        val expected = testDbApplication
          .copy(
            credentials = Set(productionDbCredential, testDbCredential)
          )

        DbApplication(application) mustBe expected
        DbApplication(application).toModel(FakeHipEnvironments).getMasterCredential(FakeHipEnvironments.productionEnvironment).value.clientSecret mustBe None
        DbApplication(application).toModel(FakeHipEnvironments).getMasterCredential(FakeHipEnvironments.testEnvironment).value.clientSecret mustBe None
      }

      "must remove team members when the application has a team Id" in {
        val application = testApplication.setTeamId("test-team-id")

        val expected = DbApplication(
          id = application.id,
          name = application.name,
          created = application.created,
          createdBy = application.createdBy,
          lastUpdated = application.lastUpdated,
          teamId = application.teamId,
          teamMembers = Seq.empty,
          apis = None,
          deleted = None,
          credentials = Set.empty
        )

        DbApplication(application) mustBe expected
      }

      "must correctly map Apis to DbApis" in {
        val api = Api("test-id", "test-title", Seq(Endpoint("endpoint-method", "endpoint-path")))
        val application = testApplication.setApis(Seq(api))

        DbApplication(application).apis mustBe Some(Seq(DbApi(api.id, Some(api.title), api.endpoints)))
      }
    }

    "when translating from DbApplication to Application" - {
      "must default a credential's created timestamp to the application's" in {
        val clientId = "test-client-id"
        val dbCredential = DbCredential(clientId, None, None, FakeHipEnvironments.productionEnvironment.id)

        val dbApplication = testDbApplication
          .copy(
            credentials = Set(dbCredential)
          )

        val expected = testApplication
          .setCredentials(
            FakeHipEnvironments.productionEnvironment, 
            Seq(Credential(clientId, testApplication.created, None, None, FakeHipEnvironments.productionEnvironment.id))
          )

        dbApplication.toModel(FakeHipEnvironments) mustBe expected
      }

      "must correctly map DbApis to Apis" in {
        val dbApi = DbApi("test-id", Some("test-title"), Seq(Endpoint("endpoint-method", "endpoint-path")))
        val dbApplication = testDbApplication.copy(apis = Some(Seq(dbApi)))

        dbApplication.toModel(FakeHipEnvironments).apis mustBe Seq(Api(dbApi.id, dbApi.title.value, dbApi.endpoints))
      }
    }
  }

}

object DbApplicationSpec {

  private val now = LocalDateTime.now()

  private val testApplication = Application(
    id = Some("test-id"),
    name = "test-name",
    created = now.minusDays(2),
    createdBy = Creator("test-creator-email"),
    lastUpdated = now.minusDays(1),
    None,
    teamMembers = Seq(TeamMember("test-creator-email"), TeamMember("test-member-email")),
    issues = Seq.empty,
    deleted = None,
    teamName = None,
    credentials = Set.empty
  )

  private val testDbApplication = DbApplication(testApplication)

}
