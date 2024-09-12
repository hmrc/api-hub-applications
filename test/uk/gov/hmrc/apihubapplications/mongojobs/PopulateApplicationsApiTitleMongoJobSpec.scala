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

package uk.gov.hmrc.apihubapplications.mongojobs

import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.when
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.connectors.IntegrationCatalogueConnector
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.application.{Api, Application, Creator, Deleted}
import uk.gov.hmrc.apihubapplications.models.exception.{ApiNotFoundException, ApplicationsException, IntegrationCatalogueException, NotUpdatedException}
import uk.gov.hmrc.apihubapplications.mongojobs.PopulateApplicationsApiTitleMongoJob.PopulationSummary
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.repositories.models.application.unencrypted.DbApi
import uk.gov.hmrc.apihubapplications.testhelpers.ApiDetailGenerators

import java.time.LocalDateTime
import scala.concurrent.Future

class PopulateApplicationsApiTitleMongoJobSpec extends AsyncFreeSpec with Matchers with MockitoSugar {

  import PopulateApplicationsApiTitleMongoJobSpec._

  "PopulateApplicationsApiTitleMongoJob" - {
    "must process applications and return the correct summary" in {
      val fixture = buildFixture()

      when(fixture.applicationsRepository.findAll(any, any, any)).thenReturn(Future.successful(applications))

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(apiDetail)))
      when(fixture.integrationCatalogueConnector.findById(eqTo(apiDetailNotFound.id))(any))
        .thenReturn(Future.successful(Left(ApiNotFoundException.forId(apiDetailNotFound.id))))
      when(fixture.integrationCatalogueConnector.findById(eqTo(apiDetailIntegrationCatalogueError.id))(any))
        .thenReturn(Future.successful(Left(IntegrationCatalogueException.unexpectedResponse(500))))

      when(fixture.applicationsRepository.update(any)).thenReturn(Future.successful(Right(())))
      when(fixture.applicationsRepository.update(eqTo(
        failedApplication1.copy(apis = Seq(Api(id = apiDetail.id, title = apiDetail.title)))
      )))
        .thenReturn(Future.successful(Left[ApplicationsException, Unit](NotUpdatedException.forApplication(failedApplication1))))

      fixture.job.migrate().map(
        result =>
        result mustBe PopulationSummary(5, 1, 1, 1, 1)
      )
    }
  }

  private case class Fixture(
    applicationsRepository: ApplicationsRepository,
    integrationCatalogueConnector: IntegrationCatalogueConnector,
    job: PopulateApplicationsApiTitleMongoJob
  )

  private def buildFixture(): Fixture = {
    val applicationsRepository = mock[ApplicationsRepository]
    val integrationCatalogueConnector = mock[IntegrationCatalogueConnector]
    val job = new PopulateApplicationsApiTitleMongoJob(applicationsRepository, integrationCatalogueConnector)

    Fixture(applicationsRepository, integrationCatalogueConnector, job)
  }

}

object PopulateApplicationsApiTitleMongoJobSpec extends ApiDetailGenerators {

  private val createdBy = Creator("test-email")
  private val team = Seq.empty
  private val deleted = Deleted(LocalDateTime.now(), "test-email")

  private val apiDetail = sampleApiDetail()
  private val apiDetailNotFound = sampleApiDetail()
  private val apiDetailIntegrationCatalogueError = sampleApiDetail()

  private val populated1 = Application(Some("poupulated-id-1"), "populated-application-1", createdBy, team)
    .copy(apis = Seq(Api(id = apiDetail.id, title = DbApi.API_NAME_UNKNOWN)))
  private val populated2 = Application(Some("poupulated-id-2"), "populated-application-2", createdBy, team)
    .copy(apis = Seq(Api(id = apiDetail.id, title = DbApi.API_NAME_UNKNOWN)))
  private val failedApplication1 = Application(Some("failed-id-1"), "failed-application-1", createdBy, team)
    .copy(apis = Seq(Api(id = apiDetail.id, title = DbApi.API_NAME_UNKNOWN)))
  private val applicationWithMissingApi = Application(Some("failed-id-1"), "failed-application-1", createdBy, team)
    .copy(apis = Seq(Api(id = apiDetailNotFound.id, title = DbApi.API_NAME_UNKNOWN)))
  private val applicationWithIntegrationCatalogueError = Application(Some("failed-id-1"), "failed-application-1", createdBy, team)
    .copy(apis = Seq(Api(id = apiDetailIntegrationCatalogueError.id, title = DbApi.API_NAME_UNKNOWN)))

  private val applications = Seq(populated1, populated2, failedApplication1, applicationWithMissingApi, applicationWithIntegrationCatalogueError)

}