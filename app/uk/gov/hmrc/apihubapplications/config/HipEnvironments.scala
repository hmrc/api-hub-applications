/*
 * Copyright 2025 HM Revenue & Customs
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

case class HipEnvironment(
  id: String,
  rank: Int,
  isProductionLike: Boolean,
  apimUrl: String,
  clientId: String,
  secret: String,
  useProxy: Boolean,
  apiKey: Option[String],
  apimEnvironmentName: String
)

object HipEnvironment {

  implicit val hipEnvironmentConfigLoader: ConfigLoader[HipEnvironment] =
    (rootConfig: Config, path: String) => {
      val config = rootConfig.getConfig(path)

      HipEnvironment(
        id = config.getString("id"),
        rank = config.getInt("rank"),
        isProductionLike = config.getBoolean("isProductionLike"),
        apimUrl = config.getString("apimUrl"),
        clientId = config.getString("clientId"),
        secret = config.getString("secret"),
        useProxy = config.getBoolean("useProxy"),
        apiKey = getOptionalString(config, "apiKey"),
        apimEnvironmentName = config.getString("apimEnvironmentName")
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

  def productionEnvironment: HipEnvironment

  def deploymentEnvironment: HipEnvironment

  def forId(environmentId: String): HipEnvironment = {
    environments
      .find(_.id == environmentId)
      .getOrElse(throw new IllegalArgumentException(s"No configuration for environment $environmentId"))
  }

  def forUrlPathParameter(pathParameter: String): Option[HipEnvironment] =  
    environments.find(hipEnvironment => hipEnvironment.id == pathParameter)

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

  override val productionEnvironment: HipEnvironment = environments.find(_.isProductionLike)
    .getOrElse(throw new IllegalArgumentException("No production environment configured"))

  override val deploymentEnvironment: HipEnvironment = environments.maxBy(_.rank)

}
