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

import com.google.inject.Inject
import play.api.inject.{Binding, bind => bindz}
import play.api.{Configuration, Environment, Logging}
import uk.gov.hmrc.mongo.lock.{LockService, MongoLockRepository}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class MongoJobModule extends play.api.inject.Module with Logging {

  override def bindings(environment: Environment, configuration: Configuration): collection.Seq[Binding[_]] = {
    val workToDo = configuration.getOptional[Boolean]("mongoJob.enabled").getOrElse(false)

    logger.info(s"Startup mongo job needs doing: $workToDo")

    if (workToDo) {
      configuration.getOptional[String]("mongoJob.className").map(
        className =>
          Seq(
            bindz(classOf[LockClient]).toSelf.eagerly(),
            bindz(classOf[MongoJob]).to(environment.classLoader.loadClass(className).asSubclass(classOf[MongoJob])).eagerly()
          )
      ).getOrElse(Seq.empty)
    }
    else {
      Seq.empty
    }
  }

}

class LockClient @Inject()(
  mongoLockRepository: MongoLockRepository,
  job: MongoJob
)(implicit ec: ExecutionContext) extends Logging{

  private val lockService = LockService(mongoLockRepository, lockId = "mongo_update_lock", ttl = 1.hour)

  lockService.withLock {
    job.run()
  }.map {
    case Some(res) => logger.info(s"Finished with $res. Lock has been released.")
    case None => logger.error("Failed to take lock")
  }

}
