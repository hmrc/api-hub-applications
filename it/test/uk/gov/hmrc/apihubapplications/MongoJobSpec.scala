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

package uk.gov.hmrc.apihubapplications

import org.mockito.Mockito.verifyNoInteractions
import org.mockito.MockitoSugar
import org.mockito.MockitoSugar.mock
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.running
import play.api.{Application => PlayApplication}
import uk.gov.hmrc.apihubapplications.MongoJobSpec.buildFixture
import uk.gov.hmrc.apihubapplications.mongojobs.ExampleJob
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository

class MongoJobSpec
  extends AnyFreeSpec
    with Matchers
    with MockitoSugar {

  "Example Mongo Job should" - {
    "lookup application indexes when mongo job is enabled" in {
      val fixture = buildFixture(Some(true))
      running(fixture.application) {
        Thread.sleep(1000)
        eventually {verify(fixture.applicationsRepository).listIndexes}
      }
    }
  }

  "not lookup application indexes when mongo job is disabled" in {
    val fixture = buildFixture(Some(false))
    running(fixture.application) {
      verifyNoInteractions(fixture.applicationsRepository)
    }
  }

  "not lookup application indexes when mongo job config is absent" in {
    val fixture = buildFixture(None)
    running(fixture.application) {
      verifyNoInteractions(fixture.applicationsRepository)
    }
  }
}

object MongoJobSpec {

  case class Fixture(application: PlayApplication,
                     applicationsRepository: ApplicationsRepository)

  def buildFixture(enableMongoJob: Option[Boolean] = None): Fixture = {
    val mockRepository = mock[ApplicationsRepository]
    var applicationBuilder = new GuiceApplicationBuilder()
      .overrides(bind[ApplicationsRepository].toInstance(mockRepository))

    if (enableMongoJob.isDefined) {
      applicationBuilder = applicationBuilder.configure(
        "mongoJob.enabled" -> enableMongoJob.get,
        "mongoJob.className" -> classOf[ExampleJob].getName
      )
    }

    Fixture(applicationBuilder.build(), mockRepository)
  }

}
