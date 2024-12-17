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
        val productionCredential = Credential("test-client-id-1", now, Some("test-secret-1"), Some("test-fragment-1"), FakeHipEnvironments.primaryEnvironment.id)
        val testCredential = Credential("test-client-id-2", now, Some("test-secret-2"), Some("test-fragment-2"), FakeHipEnvironments.secondaryEnvironment.id)

        val productionDbCredential = DbCredential(productionCredential.clientId, Some(productionCredential.created), productionCredential.secretFragment, Some(productionCredential.environmentId))
        val testDbCredential = DbCredential(testCredential.clientId, Some(testCredential.created), testCredential.secretFragment, Some(testCredential.environmentId))

        val application = testApplication
          .addCredential(Primary, productionCredential)
          .addCredential(Secondary, testCredential)

        val expected = testDbApplication
          .copy(
            environments = DbEnvironments(
              primary = DbEnvironment(Seq(productionDbCredential)),
              secondary = DbEnvironment(Seq(testDbCredential))
            ),
            credentials = Some(Set(productionDbCredential, testDbCredential))
          )

        DbApplication(application) mustBe expected
        DbApplication(application).toModel.getMasterCredential(Primary).value.clientSecret mustBe None
        DbApplication(application).toModel.getMasterCredential(Secondary).value.clientSecret mustBe None
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
          environments = DbEnvironments(Environments()),
          apis = None,
          deleted = None,
          credentials = Some(Set.empty)
        )

        DbApplication(application) mustBe expected
      }

      "must correctly map Apis to DbApis" in {
        val api = Api("test-id", "test-title", Seq(Endpoint("endpoint-method", "endpoint-path")))
        val application = testApplication.setApis(Seq(api))

        DbApplication(application).apis mustBe Some(Seq(DbApi(api.id, Some(api.title), api.endpoints)))
      }

      "must throw an exception if environments and credentials are not in sync" in {
        val credential = Credential("test-client-id", LocalDateTime.now(), None, None, FakeHipEnvironments.primaryEnvironment.id)
        val application = testApplication.copy(credentials = Set(credential))

        an[IllegalStateException] must be thrownBy DbApplication(application)
      }
    }

    "when translating from DbApplication to Application" - {
      "must default a credential's created timestamp to the application's" in {
        val clientId = "test-client-id"
        val dbCredential = DbCredential(clientId, None, None, Some(FakeHipEnvironments.primaryEnvironment.id))

        val dbApplication = testDbApplication
          .copy(
            environments = DbEnvironments(
              primary = DbEnvironment(Seq(dbCredential)),
              secondary = DbEnvironment(Seq.empty)
            ),
            credentials = Some(Set(dbCredential))
          )

        val expected = testApplication
          .setCredentials(
            Primary, 
            Seq(Credential(clientId, testApplication.created, None, None, FakeHipEnvironments.primaryEnvironment.id))
          )

        dbApplication.toModel mustBe expected
      }

      "must correctly map DbApis to Apis" in {
        val dbApi = DbApi("test-id", Some("test-title"), Seq(Endpoint("endpoint-method", "endpoint-path")))
        val dbApplication = testDbApplication.copy(apis = Some(Seq(dbApi)))

        dbApplication.toModel.apis mustBe Seq(Api(dbApi.id, dbApi.title.value, dbApi.endpoints))
      }

      "must throw an exception if environments and credentials are not in sync" in {
        val dbCredential = DbCredential("test-client-id", None, None, None)
        val dbApplication = testDbApplication.copy(credentials = Some(Set(dbCredential)))

        an[IllegalStateException] must be thrownBy dbApplication.toModel
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
    environments = Environments(),
    issues = Seq.empty,
    deleted = None,
    teamName = None,
    credentials = Set.empty
  )

  private val testDbApplication = DbApplication(testApplication)

}
