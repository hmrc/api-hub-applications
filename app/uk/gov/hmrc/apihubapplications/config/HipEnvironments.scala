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

import scala.annotation.tailrec

// Define the attributes of an environment in an abstract way
trait AbstractHipEnvironment[T] {
  def id: String
  def rank: Int
  def isProductionLike: Boolean
  def apimUrl: String
  def clientId: String
  def secret: String
  def useProxy: Boolean
  def apiKey: Option[String]
  def promoteTo: Option[T]
}

// This can be serialised/de-serialised to support REST calls
// It doesn't have promoteTo as Option[HipEnvironment] which isn't nice to code with
// Use this as the input to HipEnvironments and to pass from BE to FE
case class BaseHipEnvironment(
  id: String,
  rank: Int,
  isProductionLike: Boolean,
  apimUrl: String,
  clientId: String,
  secret: String,
  useProxy: Boolean,
  apiKey: Option[String],
  promoteTo: Option[String]
) extends AbstractHipEnvironment[String]

// Nicer, as promoteTo now gives us a HipEnvironment
// Use this everywhere in code
// We want to do some hooky lazy val stuff below so a trait is good
trait HipEnvironment extends AbstractHipEnvironment[HipEnvironment]

// Default implementation for use in fakes etc
case class DefaultHipEnvironment(
  id: String,
  rank: Int,
  isProductionLike: Boolean,
  apimUrl: String,
  clientId: String,
  secret: String,
  useProxy: Boolean,
  apiKey: Option[String],
  promoteTo: Option[HipEnvironment]
) extends HipEnvironment

trait HipEnvironments {

  protected def baseEnvironments: Seq[BaseHipEnvironment]

  def environments: Seq[HipEnvironment] = {
    baseEnvironments
      .map(
        base =>
          new Object with HipEnvironment:
            override val id: String = base.id
            override val rank: Int = base.rank
            override val isProductionLike: Boolean = base.isProductionLike
            override val apimUrl: String = base.apimUrl
            override val clientId: String = base.clientId
            override val secret: String = base.secret
            override val useProxy: Boolean = base.useProxy
            override val apiKey: Option[String] = base.apiKey
            override lazy val promoteTo: Option[HipEnvironment] = base.promoteTo.map(forId)
      )
      .sortBy(_.rank)
  }

  def production: HipEnvironment

  def deployTo: HipEnvironment

  def validateIn: HipEnvironment

  def forId(environmentId: String): HipEnvironment = {
    environments
      .find(_.id == environmentId)
      .getOrElse(throw new IllegalArgumentException(s"No configuration for environment $environmentId"))
  }

  def forUrlPathParameter(pathParameter: String): Option[HipEnvironment] =
    environments.find(hipEnvironment => hipEnvironment.id == pathParameter)

}

// In the frontend we would have a RestHipEnvironmentsImpl
// This would need a lazy baseEnvironments that would be fetched on demand from the BE
@Singleton
class ConfigurationHipEnvironmentsImpl @Inject(configuration: Configuration) extends HipEnvironments {

  import ConfigurationHipEnvironmentsImpl._

  private val baseConfig = buildBaseConfig(configuration)

  override protected val baseEnvironments: Seq[BaseHipEnvironment] = {
    buildEnvironments(baseConfig)
  }

  override def production: HipEnvironment = environments.find(_.id == baseConfig.production).head

  override def deployTo: HipEnvironment = environments.find(_.id == baseConfig.deployTo).head

  override def validateIn: HipEnvironment = environments.find(_.id == baseConfig.validateIn).head

}

object ConfigurationHipEnvironmentsImpl {

  case class ConfigHipEnvironment(
    id: String,
    rank: Int,
    apimUrl: String,
    clientId: String,
    secret: String,
    useProxy: Boolean,
    apiKey: Option[String],
    promoteTo: Option[String]
  )

  object ConfigHipEnvironment {

    implicit val hipEnvironmentConfigLoader: ConfigLoader[ConfigHipEnvironment] =
      (rootConfig: Config, path: String) => {
        val config = rootConfig.getConfig(path)

        ConfigHipEnvironment(
          id = config.getString("id"),
          rank = config.getInt("rank"),
          apimUrl = config.getString("apimUrl"),
          clientId = config.getString("clientId"),
          secret = config.getString("secret"),
          useProxy = config.getBoolean("useProxy"),
          apiKey = getOptionalString(config, "apiKey"),
          promoteTo = getOptionalString(config, "promoteTo"),
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

  case class BaseConfig(
    environments: Seq[ConfigHipEnvironment],
    production: String,
    deployTo: String,
    validateIn: String
  )

  def buildBaseConfig(configuration: Configuration): BaseConfig = {
    val environments = configuration
      .get[Map[String, ConfigHipEnvironment]]("hipEnvironments.environments")
      .values
      .toSeq

    val production = configuration.get[String]("hipEnvironments.production")
    val deployTo = configuration.get[String]("hipEnvironments.deployTo")
    val validateIn = configuration.get[String]("hipEnvironments.validateIn")

    BaseConfig(environments, production, deployTo, validateIn)
  }

  def buildEnvironments(baseConfig: BaseConfig): Seq[BaseHipEnvironment] = {
    validate(baseConfig)

    baseConfig.environments
      .map(
        base => BaseHipEnvironment(
          id = base.id,
          rank = base.rank,
          isProductionLike = base.id == baseConfig.production,
          apimUrl = base.apimUrl,
          clientId = base.clientId,
          secret = base.secret,
          useProxy = base.useProxy,
          apiKey = base.apiKey,
          promoteTo = base.promoteTo
        )

      )
  }

  def validate(baseConfig: BaseConfig): Unit = {
    /*
      Validation rules?
        rank - unique, contiguous, min is 1, max is array size
        id - unique, URL safe
        isProductionLike - only one, must match production
        production - must be real Id's
        deployTo - must be real Id's
        validateIn - must be real Id's
        promoteTo - must be real Id's, not cyclical
        apimUrl - valid URL
        apiKey - mandatory if useProxy is true
     */
  }

}
