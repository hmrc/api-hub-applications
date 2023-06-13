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

package uk.gov.hmrc.apihubapplications.mongojobs

import com.google.inject.{AbstractModule, Inject}
import play.api.{Configuration, Logging}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.mongo.lock.{LockService, MongoLockRepository}

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class MongoJob extends AbstractModule with Logging {
  override def configure(): Unit = {
    bind(classOf[LockClient]).asEagerSingleton()
  }
}

class LockClient @Inject()(mongoLockRepository: MongoLockRepository,
                           applicationsRepository: ApplicationsRepository,
                           config: Configuration)(implicit ec: ExecutionContext) extends Logging{

  private val workToDo: Boolean = config.getOptional[Boolean]("mongoJobEnabled").getOrElse(false)

  logger.info(s"Startup mongo job needs doing: $workToDo")

  if (workToDo) {
    val lockService = LockService(mongoLockRepository, lockId = "mongo_update_lock", ttl = 1.hour)

    lockService.withLock {
      runJob
    }.map {
      case Some(res) => logger.debug(s"Finished with $res. Lock has been released.")
      case None => logger.debug("Failed to take lock")
    }
  }


  def runJob = {
    logger.info(s"Example mongo job is running...")
    applicationsRepository.findById(UUID.randomUUID().toString)
  }
}