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
import play.api.libs.json.{Format, Json}
import play.api.{ConfigLoader, Configuration}
import uk.gov.hmrc.apihubapplications.models.config.ShareableHipConfig

import java.net.URL
import scala.annotation.tailrec
import scala.util.Try

// Define the attributes of an environment in an abstract way
trait AbstractHipEnvironment[T] {
  def id: String
  def name: String
  def rank: Int
  def isProductionLike: Boolean
  def apimUrl: String
  def clientId: String
  def secret: String
  def useProxy: Boolean
  def apiKey: Option[String]
  def promoteTo: Option[T]
  def apimEnvironmentName: String
}

// This can be serialised/de-serialised to support REST calls
// It doesn't have promoteTo as Option[HipEnvironment] which isn't nice to code with
// Use this as the input to HipEnvironments and to pass from BE to FE
case class BaseHipEnvironment(
  id: String,
  name: String,
  rank: Int,
  isProductionLike: Boolean,
  apimUrl: String,
  clientId: String,
  secret: String,
  useProxy: Boolean,
  apiKey: Option[String],
  promoteTo: Option[String],
  apimEnvironmentName: String
) extends AbstractHipEnvironment[String]

object BaseHipEnvironment {
  implicit val formatBaseHipEnvironment: Format[BaseHipEnvironment] = Json.format[BaseHipEnvironment]
}

// Nicer, as promoteTo now gives us a HipEnvironment
// Use this everywhere in code
// We want to do some hooky lazy val stuff below so a trait is good
trait HipEnvironment extends AbstractHipEnvironment[HipEnvironment]

// Default implementation for use in fakes etc
case class DefaultHipEnvironment(
  id: String,
  name: String,
  rank: Int,
  isProductionLike: Boolean,
  apimUrl: String,
  clientId: String,
  secret: String,
  useProxy: Boolean,
  apiKey: Option[String],
  promoteTo: Option[HipEnvironment],
  apimEnvironmentName: String
) extends HipEnvironment

object DefaultHipEnvironment {

  def apply(hipEnvironment: HipEnvironment): DefaultHipEnvironment = {
    DefaultHipEnvironment(
      id = hipEnvironment.id,
      name = hipEnvironment.name,
      rank = hipEnvironment.rank,
      isProductionLike = hipEnvironment.isProductionLike,
      apimUrl = hipEnvironment.apimUrl,
      clientId = hipEnvironment.clientId,
      secret = hipEnvironment.secret,
      useProxy = hipEnvironment.useProxy,
      apiKey = hipEnvironment.apiKey,
      promoteTo = hipEnvironment.promoteTo,
      apimEnvironmentName = hipEnvironment.apimEnvironmentName
    )
  }

}

trait HipEnvironments {

  protected def baseEnvironments: Seq[BaseHipEnvironment]

  def environments: Seq[HipEnvironment] = {
    baseEnvironments
      .map(
        base =>
          new Object with HipEnvironment:
            override val id: String = base.id
            override val name: String = base.name
            override val rank: Int = base.rank
            override val isProductionLike: Boolean = base.isProductionLike
            override val apimUrl: String = base.apimUrl
            override val clientId: String = base.clientId
            override val secret: String = base.secret
            override val useProxy: Boolean = base.useProxy
            override val apiKey: Option[String] = base.apiKey
            override lazy val promoteTo: Option[HipEnvironment] = base.promoteTo.map(forId)
            override val apimEnvironmentName: String = base.apimEnvironmentName
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

  def asShareableEnvironments() : ShareableHipConfig = ShareableHipConfig(baseEnvironments, production.id, deployTo.id)
}

// In the frontend we would have a RestHipEnvironmentsImpl
// This would need a lazy baseEnvironments that would be fetched on demand from the BE
@Singleton
class ConfigurationHipEnvironmentsImpl @Inject(configuration: Configuration) extends HipEnvironments {

  import ConfigurationHipEnvironmentsImpl.*

  val baseConfig = buildBaseConfig(configuration)

  override val baseEnvironments: Seq[BaseHipEnvironment] = {
    buildEnvironments(baseConfig)
  }

  override def production: HipEnvironment = environments.find(_.id == baseConfig.production).head

  override def deployTo: HipEnvironment = environments.find(_.id == baseConfig.deployTo).head

  override def validateIn: HipEnvironment = environments.find(_.id == baseConfig.validateIn).head

}

object ConfigurationHipEnvironmentsImpl {

  case class ConfigHipEnvironment(
    id: String,
    name: String,
    rank: Int,
    apimUrl: String,
    clientId: String,
    secret: String,
    useProxy: Boolean,
    apiKey: Option[String],
    promoteTo: Option[String],
    apimEnvironmentName: String,
    isProductionLike: Boolean,
    used: Boolean
  )

  object ConfigHipEnvironment {

    implicit val hipEnvironmentConfigLoader: ConfigLoader[ConfigHipEnvironment] =
      (rootConfig: Config, path: String) => {
        val config = rootConfig.getConfig(path)

        ConfigHipEnvironment(
          id = config.getString("id"),
          name = config.getString("name"),
          rank = config.getInt("rank"),
          apimUrl = config.getString("apimUrl"),
          clientId = config.getString("clientId"),
          secret = config.getString("secret"),
          useProxy = config.getBoolean("useProxy"),
          apiKey = getOptionalString(config, "apiKey"),
          promoteTo = getOptionalString(config, "promoteTo"),
          apimEnvironmentName = config.getString("apimEnvironmentName"),
          isProductionLike = config.getBoolean("isProductionLike"),
          used = config.getBoolean("used")
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
  ) {
    def usedEnvironments: Seq[ConfigHipEnvironment] = environments.filter(_.used)
  }

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

    baseConfig.usedEnvironments
      .map(
        base => BaseHipEnvironment(
          id = base.id,
          name = base.name,
          rank = base.rank,
          isProductionLike = base.isProductionLike,
          apimUrl = base.apimUrl,
          clientId = base.clientId,
          secret = base.secret,
          useProxy = base.useProxy,
          apiKey = base.apiKey,
          promoteTo = base.promoteTo,
          apimEnvironmentName = base.apimEnvironmentName
        )

      )
  }

  private def validate(baseConfig: BaseConfig): Unit = {
    validateRanks(baseConfig.environments)
    validateIds(baseConfig.environments)
    validateProduction(baseConfig)
    validateDeployTo(baseConfig)
    validateValidateIn(baseConfig)
    validatePromoteTos(baseConfig)
    validateApimUrls(baseConfig.environments)
    validateApiKeys(baseConfig.environments)
  }

  private def validateRanks(environments: Seq[ConfigHipEnvironment]): Unit = {
    // ranks mandatory, start at 1, be contiguous,  
    val actualRanks = environments.map(_.rank).toSet
    val expectedRanks = Seq.range(1, environments.size + 1).toSet
    if (!actualRanks.equals(expectedRanks)) {
      throw new IllegalArgumentException("Hip environments must have valid ranks.")
    }
  }

  private def validateIds(environments: Seq[ConfigHipEnvironment]): Unit = {
    // ids must be unique and URL safe
    val actualIds = environments.map(_.id).toSet
    if (!actualIds.size.equals(environments.size)) {
      throw new IllegalArgumentException("Hip environment ids must be unique.")
    }
    if (actualIds.exists(id => !id.matches("[A-Za-z0-9-_.~]+"))) {
      throw new IllegalArgumentException("Hip environment ids must only contain URL unreserved characters.")
    }
  }

  private def validateProduction(baseConfig: BaseConfig): Unit = {
    // production id must be real, and production cannot promote
    val productionEnvironment = baseConfig.usedEnvironments
      .find(_.id == baseConfig.production)
      .getOrElse(throw new IllegalArgumentException(s"production id ${baseConfig.production} must match one of the configured environments."))
    if (productionEnvironment.promoteTo.isDefined) {
      throw new IllegalArgumentException(s"production environment cannot promote to anywhere.")
    }
    // production must be productionLike
    if (!productionEnvironment.isProductionLike) {
      throw new IllegalArgumentException(s"production environment must be productionLike.")
    }


  }

  private def validateDeployTo(baseConfig: BaseConfig): Unit = {
    // deployTo must be real
    baseConfig.usedEnvironments.find(_.id == baseConfig.deployTo).getOrElse(throw new IllegalArgumentException(s"deployTo id ${baseConfig.deployTo} must match one of the configured environments."))
  }

  private def validateValidateIn(baseConfig: BaseConfig): Unit = {
    // validateIn must be real
    baseConfig.usedEnvironments.find(_.id == baseConfig.validateIn).getOrElse(throw new IllegalArgumentException(s"validateIn id ${baseConfig.validateIn} must match one of the configured environments."))
  }

  private def validatePromoteTos(baseConfig: BaseConfig): Unit = {
    // promoteTo must be real, unique, and not cyclical
    val actualIds = baseConfig.usedEnvironments.map(_.id).toSet
    val actualPromoteTos = baseConfig.usedEnvironments.flatMap(_.promoteTo)
    val actualPromoteTosSet = actualPromoteTos.toSet

    if (!actualPromoteTos.size.equals(actualPromoteTosSet.size)) {
      throw new IllegalArgumentException("promoteTo ids must be unique.")
    }

    if (!actualPromoteTosSet.subsetOf(actualIds)) {
      throw new IllegalArgumentException("promoteTo ids must be real.")
    }

    baseConfig.usedEnvironments.foreach(env => {
      val thisEnvId = env.id
      var promoteTo = env.promoteTo
      while (promoteTo.isDefined) {
        if (promoteTo.get.equals(thisEnvId)) {
          throw new IllegalArgumentException("environments cannot cyclically promoteTo themselves.")
        }
        promoteTo = baseConfig.usedEnvironments.find(_.id == promoteTo.get).flatMap(_.promoteTo)
      }
    })
  }

  private def validateApimUrls(environments: Seq[ConfigHipEnvironment]): Unit = {
    // must be valid url
    environments.foreach(env => {
      val value = Try(new URL(env.apimUrl)).toOption
      if (value.isEmpty) {
        throw new IllegalArgumentException("environments must have a valid apimUrl.")
      }
    })
  }

  private def validateApiKeys(environments: Seq[ConfigHipEnvironment]): Unit = {
    // environments with useProxy must have apiKey
    if (environments.filter(_.useProxy).exists(_.apiKey.isEmpty)) {
      throw new IllegalArgumentException("environments with useProxy=true must have an apiKey.")
    }
  }


}
