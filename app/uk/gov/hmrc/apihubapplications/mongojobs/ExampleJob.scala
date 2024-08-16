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

import com.google.inject.Inject
import play.api.Logging
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository

import scala.concurrent.{ExecutionContext, Future}

class ExampleJob @Inject()(applicationsRepository: ApplicationsRepository)(implicit ec: ExecutionContext) extends MongoJob with Logging {

  override def run(): Future[Unit] = {
    logger.info(s"Example mongo job is running...")
    applicationsRepository.listIndexes.foreach(i => logger.info(s"Index: $i"))
    Future(())
  }

}
