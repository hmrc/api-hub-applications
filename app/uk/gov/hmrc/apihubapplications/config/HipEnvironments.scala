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

package uk.gov.hmrc.apihubapplications.config

import com.google.inject.{Inject, Singleton}
import com.typesafe.config.Config
import play.api.{ConfigLoader, Configuration}
import uk.gov.hmrc.apihubapplications.models.application.EnvironmentName

case class HipEnvironment(
  id: String,
  rank: Int,                          // 1 is production
  environmentName: EnvironmentName,   // Needed to pass environment round existing code and in URL paths to backend
  isProductionLike: Boolean,
  idmsUrl: String,
  apimUrl: String,
  clientId: String,
  secret: String,
  useProxy: Boolean,
  apiKey: Option[String]
)

object HipEnvironment {

  implicit val hipEnvironmentConfigLoader: ConfigLoader[HipEnvironment] =
    (rootConfig: Config, path: String) => {
      val config = rootConfig.getConfig(path)

      HipEnvironment(
        id = config.getString("id"),
        rank = config.getInt("rank"),
        environmentName = EnvironmentName.enumerable.withName(config.getString("environmentName")).getOrElse(throw new IllegalArgumentException()),
        isProductionLike = config.getBoolean("isProductionLike"),
        idmsUrl = config.getString("idmsUrl"),
        apimUrl = config.getString("apimUrl"),
        clientId = config.getString("clientId"),
        secret = config.getString("secret"),
        useProxy = config.getBoolean("useProxy"),
        apiKey = getOptionalString(config, "apiKey")
      )
    }

  private def getOptionalString(config: Config, path: String): Option[String] = {
    if (config.hasPath(path)) {
      Some(config.getString(path))
    }
    else {
      None
    }
  }

}

trait HipEnvironments {

  def environments: Seq[HipEnvironment]

  def forEnvironmentName(environmentName: EnvironmentName): HipEnvironment = {
    forEnvironmentNameOption(environmentName)
      .getOrElse(throw new IllegalArgumentException(s"No configuration for environment $environmentName"))
  }

  def forEnvironmentNameOption(environmentName: EnvironmentName): Option[HipEnvironment] = {
    environments
      .find(_.environmentName == environmentName)
  }

}

@Singleton
class HipEnvironmentsImpl @Inject(configuration: Configuration) extends HipEnvironments {

  override val environments: Seq[HipEnvironment] = {
    configuration
      .get[Map[String, HipEnvironment]]("hipEnvironments")
      .values
      .toSeq
      .sortBy(_.rank)
  }

}
