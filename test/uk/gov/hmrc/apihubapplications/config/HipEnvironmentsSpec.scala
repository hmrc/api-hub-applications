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

import com.typesafe.config.ConfigFactory
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration

class HipEnvironmentsSpec  extends AsyncFreeSpec with Matchers with MockitoSugar {

  "HipEnvironments" - {
    val hipEnvironments = new ConfigurationHipEnvironmentsImpl(Configuration(ConfigFactory.parseString(
      s"""
         |hipEnvironments = {
         |    environments = {
         |        production = {
         |            id = "production",
         |            name = "Production",
         |            rank = 1,
         |            apimUrl = "http://localhost:15026/api-hub-apim-stubs"
         |            clientId = "apim-stub-client-id",
         |            secret = "apim-stub-secret",
         |            useProxy = false,
         |            apimEnvironmentName = "production",
         |            isProductionLike = true,
         |            used = true
         |        },
         |        test = {
         |            id = "test",
         |            name = "Test",
         |            rank = 2,
         |            apimUrl = "http://localhost:15027/apim-proxy/api-hub-apim-stubs"
         |            clientId = "apim-stub-client-id",
         |            secret = "apim-stub-secret",
         |            useProxy = true,
         |            apiKey = "some-magic-key"
         |            promoteTo = "production",
         |            apimEnvironmentName = "test",
         |            isProductionLike = false,
         |            used = true
         |        }
         |    },
         |    production = "production",
         |    deployTo = "test",
         |    validateIn = "production"
         |}
         |""".stripMargin)))
    "must have its environments in the right order" in {
      hipEnvironments.environments.map(_.id) must contain theSameElementsInOrderAs Seq("production", "test")
    }
    "must return the correct production environment" in {
      hipEnvironments.production.id mustBe "production"
    }
    "must return the correct deployment environment" in {
      hipEnvironments.deployTo.id mustBe "test"
    }
    "must have contiguous ranks" in {
      val e = the[IllegalArgumentException] thrownBy (new ConfigurationHipEnvironmentsImpl(Configuration(ConfigFactory.parseString(
        s"""
           |hipEnvironments = {
           |    environments = {
           |        production = {
           |            id = "production",
           |            name = "Production",
           |            rank = 1,
           |            apimUrl = "http://localhost:15026/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = false,
           |            apimEnvironmentName = "production"
           |            isProductionLike = true,
           |            used = true
           |        },
           |        test = {
           |            id = "test",
           |            name = "Test",
           |            rank = 3,
           |            apimUrl = "http://localhost:15027/apim-proxy/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = true,
           |            apiKey = "some-magic-key"
           |            promoteTo = "production",
           |            apimEnvironmentName = "test",
           |            isProductionLike = false,
           |            used = true
           |        }
           |    },
           |    production = "production",
           |    deployTo = "test",
           |    validateIn = "production"
           |}
           |""".stripMargin))))

      e.getMessage mustBe ("Hip environments must have valid ranks.")
    }
    "must have as many ranks as there are environments ranks" in {
      val e = the[IllegalArgumentException] thrownBy (new ConfigurationHipEnvironmentsImpl(Configuration(ConfigFactory.parseString(
        s"""
           |hipEnvironments = {
           |    environments = {
           |        production = {
           |            id = "production",
           |            name = "Production",
           |            rank = 1,
           |            apimUrl = "http://localhost:15026/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = false,
           |            apimEnvironmentName = "production",
           |            isProductionLike = true,
           |            used = true
           |        },
           |        test = {
           |            id = "test",
           |            name = "Test",
           |            rank = 1,
           |            apimUrl = "http://localhost:15027/apim-proxy/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = true,
           |            apiKey = "some-magic-key"
           |            promoteTo = "production",
           |            apimEnvironmentName = "test",
           |            isProductionLike = false,
           |            used = true
           |        }
           |    },
           |    production = "production",
           |    deployTo = "test",
           |    validateIn = "production"
           |}
           |""".stripMargin))))

      e.getMessage mustBe "Hip environments must have valid ranks."
    }
    "must have ranks starting at 1" in {
      val e = the[IllegalArgumentException] thrownBy (new ConfigurationHipEnvironmentsImpl(Configuration(ConfigFactory.parseString(
        s"""
           |hipEnvironments = {
           |    environments = {
           |        production = {
           |            id = "production",
           |            name = "Production",
           |            rank = 2,
           |            apimUrl = "http://localhost:15026/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = false,
           |            apimEnvironmentName = "production",
           |            isProductionLike = true,
           |            used = true
           |        },
           |        test = {
           |            id = "test",
           |            name = "Test",
           |            rank = 3,
           |            apimUrl = "http://localhost:15027/apim-proxy/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = true,
           |            apiKey = "some-magic-key"
           |            promoteTo = "production",
           |            apimEnvironmentName = "test",
           |            isProductionLike = false,
           |            used = true
           |        }
           |    },
           |    production = "production",
           |    deployTo = "test",
           |    validateIn = "production"
           |}
           |""".stripMargin))))
      e.getMessage mustBe "Hip environments must have valid ranks."

    }
    "must have unique ids" in {
      val e = the[IllegalArgumentException] thrownBy (new ConfigurationHipEnvironmentsImpl(Configuration(ConfigFactory.parseString(
        s"""
           |hipEnvironments = {
           |    environments = {
           |        production = {
           |            id = "production",
           |            name = "Production",
           |            rank = 1,
           |            apimUrl = "http://localhost:15026/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = false,
           |            apimEnvironmentName = "production",
           |            isProductionLike = true,
           |            used = true
           |        },
           |        test = {
           |            id = "production",
           |            name = "Production",
           |            rank = 2,
           |            apimUrl = "http://localhost:15027/apim-proxy/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = true,
           |            apiKey = "some-magic-key"
           |            promoteTo = "production",
           |            apimEnvironmentName = "test",
           |            isProductionLike = false,
           |            used = true
           |        }
           |    },
           |    production = "production",
           |    deployTo = "test",
           |    validateIn = "production"
           |}
           |""".stripMargin))))

      e.getMessage mustBe "Hip environment ids must be unique."
    }
    "must have a real production environment" in {
      val e = the[IllegalArgumentException] thrownBy (new ConfigurationHipEnvironmentsImpl(Configuration(ConfigFactory.parseString(
        s"""
           |hipEnvironments = {
           |    environments = {
           |        production = {
           |            id = "production",
           |            name = "Production",
           |            rank = 1,
           |            apimUrl = "http://localhost:15026/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = false,
           |            apimEnvironmentName = "production",
           |            isProductionLike = true,
           |            used = true
           |        },
           |        productionish = {
           |            id = "productionish",
           |            name = "Productionish",
           |            rank = 2,
           |            apimUrl = "http://localhost:15026/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = false,
           |            apimEnvironmentName = "production",
           |            isProductionLike = true,
           |            used = false
           |        },
           |        test = {
           |            id = "test",
           |            name = "Test",
           |            rank = 3,
           |            apimUrl = "http://localhost:15027/apim-proxy/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = true,
           |            apiKey = "some-magic-key"
           |            promoteTo = "production",
           |            apimEnvironmentName = "test",
           |            isProductionLike = false,
           |            used = true
           |        }
           |    },
           |    production = "productionish",
           |    deployTo = "test",
           |    validateIn = "production"
           |}
           |""".stripMargin))))

      e.getMessage mustBe "production id productionish must match one of the configured environments."
    }
    "must not allow promotion from production environment" in {
      val e = the[IllegalArgumentException] thrownBy (new ConfigurationHipEnvironmentsImpl(Configuration(ConfigFactory.parseString(
        s"""
           |hipEnvironments = {
           |    environments = {
           |        production = {
           |            id = "production",
           |            name = "Production",
           |            rank = 1,
           |            apimUrl = "http://localhost:15026/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = false,
           |            apimEnvironmentName = "production",
           |            promoteTo = "brigadoon",
           |            isProductionLike = true,
           |            used = true
           |        },
           |        brigadoon = {
           |            id = "brigadoon",
           |            name = "Brigadoon",
           |            rank = 2,
           |            apimUrl = "http://localhost:15027/apim-proxy/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = true,
           |            apiKey = "some-magic-key"
           |            apimEnvironmentName = "brigadoon",
           |            isProductionLike = false,
           |            used = true
           |        }
           |    },
           |    production = "production",
           |    deployTo = "production",
           |    validateIn = "production"
           |}
           |""".stripMargin))))
      e.getMessage mustBe "production environment cannot promote to anywhere."
    }
    "must have a productionLike production environment" in {
      val e = the[IllegalArgumentException] thrownBy (new ConfigurationHipEnvironmentsImpl(Configuration(ConfigFactory.parseString(
        s"""
           |hipEnvironments = {
           |    environments = {
           |        production = {
           |            id = "production",
           |            name = "Production",
           |            rank = 1,
           |            apimUrl = "http://localhost:15026/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = false,
           |            apimEnvironmentName = "production",
           |            isProductionLike = false,
           |            used = true
           |        }
           |    },
           |    production = "production",
           |    deployTo = "production",
           |    validateIn = "production"
           |}
           |""".stripMargin))))

      e.getMessage mustBe "production environment must be productionLike."
    }

    "must have a real deployTo environment" in {
      val e = the[IllegalArgumentException] thrownBy (new ConfigurationHipEnvironmentsImpl(Configuration(ConfigFactory.parseString(
        s"""
           |hipEnvironments = {
           |    environments = {
           |        production = {
           |            id = "production",
           |            name = "Production",
           |            rank = 1,
           |            apimUrl = "http://localhost:15026/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = false,
           |            apimEnvironmentName = "production",
           |            isProductionLike = true,
           |            used = true
           |        },
           |        test = {
           |            id = "test",
           |            name = "Test",
           |            rank = 2,
           |            apimUrl = "http://localhost:15027/apim-proxy/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = true,
           |            apiKey = "some-magic-key"
           |            promoteTo = "production",
           |            apimEnvironmentName = "test",
           |            isProductionLike = false,
           |            used = true
           |        },
           |        testish = {
           |            id = "testish",
           |            name = "Testish",
           |            rank = 3,
           |            apimUrl = "http://localhost:15027/apim-proxy/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = true,
           |            apiKey = "some-magic-key"
           |            promoteTo = "production",
           |            apimEnvironmentName = "test",
           |            isProductionLike = false,
           |            used = false
           |        }
           |    },
           |    production = "production",
           |    deployTo = "testish",
           |    validateIn = "production"
           |}
           |""".stripMargin))))
      e.getMessage mustBe "deployTo id testish must match one of the configured environments."
    }

    "must have a real validateIn environment" in {
      val e = the[IllegalArgumentException] thrownBy (new ConfigurationHipEnvironmentsImpl(Configuration(ConfigFactory.parseString(
        s"""
           |hipEnvironments = {
           |    environments = {
           |        production = {
           |            id = "production",
           |            name = "Production",
           |            rank = 1,
           |            apimUrl = "http://localhost:15026/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = false,
           |            apimEnvironmentName = "production",
           |            isProductionLike = true,
           |            used = true
           |        },
           |        test = {
           |            id = "test",
           |            name = "Test",
           |            rank = 2,
           |            apimUrl = "http://localhost:15027/apim-proxy/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = true,
           |            apiKey = "some-magic-key"
           |            promoteTo = "production",
           |            apimEnvironmentName = "test",
           |            isProductionLike = false,
           |            used = true
           |        },
           |        validate = {
           |            id = "validate",
           |            name = "Validate",
           |            rank = 3,
           |            apimUrl = "http://localhost:15027/apim-proxy/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = true,
           |            apiKey = "some-magic-key"
           |            promoteTo = "production",
           |            apimEnvironmentName = "test",
           |            isProductionLike = false,
           |            used = false
           |        }
           |    },
           |    production = "production",
           |    deployTo = "test",
           |    validateIn = "validate"
           |}
           |""".stripMargin))))
      e.getMessage mustBe "validateIn id validate must match one of the configured environments."
    }

    "can only promoteTo real environment" in {
      val e = the[IllegalArgumentException] thrownBy (new ConfigurationHipEnvironmentsImpl(Configuration(ConfigFactory.parseString(
        s"""
           |hipEnvironments = {
           |    environments = {
           |        production = {
           |            id = "production",
           |            name = "Production",
           |            rank = 1,
           |            apimUrl = "http://localhost:15026/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = false,
           |            apimEnvironmentName = "production",
           |            isProductionLike = true,
           |            used = true
           |        },
           |        test = {
           |            id = "test",
           |            name = "Test",
           |            rank = 2,
           |            apimUrl = "http://localhost:15027/apim-proxy/api-hub-apim-stubs"
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = true,
           |            apiKey = "some-magic-key"
           |            promoteTo = "productionish",
           |            apimEnvironmentName = "test",
           |            isProductionLike = false,
           |            used = true
           |        }
           |    },
           |    production = "production",
           |    deployTo = "test",
           |    validateIn = "production"
           |}
           |""".stripMargin))))
      e.getMessage mustBe "promoteTo ids must be real."
    }
    "can not have cyclic promoteTo chains" in {
      val e = the[IllegalArgumentException] thrownBy (new ConfigurationHipEnvironmentsImpl(Configuration(ConfigFactory.parseString(
        s"""
           |hipEnvironments = {
           |    environments = {
           |      production = {
           |            id = "production",
           |            name = "Production",
           |            rank = 1,
           |            apimUrl = "http://localhost:15026/api-hub-apim-stubs",
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = false,
           |            apimEnvironmentName = "production",
           |            isProductionLike = true,
           |            used = true
           |        },
           |        coventry = {
           |            id = "coventry",
           |            name = "Coventry",
           |            rank = 2,
           |            apimUrl = "http://localhost:15026/api-hub-apim-stubs",
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = false,
           |            apimEnvironmentName = "coventry",
           |            promoteTo = "test",
           |            isProductionLike = false,
           |            used = true
           |        },
           |        preProduction = {
           |            id = "preProduction",
           |            name = "Pre-production",
           |            rank = 3,
           |            apimUrl = "http://localhost:15026/api-hub-apim-stubs",
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = false,
           |            apimEnvironmentName = "preProduction",
           |            promoteTo = "coventry",
           |            isProductionLike = true,
           |            used = true
           |        },
           |        test = {
           |            id = "test",
           |            name = "Test",
           |            rank = 4,
           |            apimUrl = "http://localhost:15027/apim-proxy/api-hub-apim-stubs",
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = true,
           |            apiKey = "some-magic-key",
           |            promoteTo = "preProduction",
           |            apimEnvironmentName = "test",
           |            isProductionLike = false,
           |            used = true
           |        }
           |    },
           |    production = "production"
           |    deployTo = "test",
           |    validateIn = "preProduction"
           |}
           |""".stripMargin))))
      e.getMessage mustBe "environments cannot cyclically promoteTo themselves."
    }
    "must have valid apimUrls" in {
      val e = the[IllegalArgumentException] thrownBy (new ConfigurationHipEnvironmentsImpl(Configuration(ConfigFactory.parseString(
        s"""
           |hipEnvironments = {
           |    environments = {
           |      production = {
           |            id = "production",
           |            name = "Production",
           |            rank = 1,
           |            apimUrl = "httq://localhost:15026/api-hub-apim-stubs",
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = false,
           |            apimEnvironmentName = "production",
           |            isProductionLike = true,
           |            used = true
           |        }
           |    }
           |    production = "production"
           |    deployTo = "production",
           |    validateIn = "production"
           |}
           |""".stripMargin))))
      e.getMessage mustBe "environments must have a valid apimUrl."
    }
    "must have apiKeys where useProxy=true" in {
      val e = the[IllegalArgumentException] thrownBy (new ConfigurationHipEnvironmentsImpl(Configuration(ConfigFactory.parseString(
        s"""
           |hipEnvironments = {
           |    environments = {
           |      production = {
           |            id = "production",
           |            name = "Production",
           |            rank = 1,
           |            apimUrl = "http://localhost:15026/api-hub-apim-stubs",
           |            clientId = "apim-stub-client-id",
           |            secret = "apim-stub-secret",
           |            useProxy = true,
           |            apimEnvironmentName = "production",
           |            isProductionLike = true,
           |            used = true
           |        }
           |    }
           |    production = "production"
           |    deployTo = "production",
           |    validateIn = "production"
           |}
           |""".stripMargin))))
      e.getMessage mustBe "environments with useProxy=true must have an apiKey."
    }
  }
  "can have multiple productionLike environments" in {
    new ConfigurationHipEnvironmentsImpl(Configuration(ConfigFactory.parseString(
      s"""
         |hipEnvironments = {
         |    environments = {
         |      production = {
         |            id = "production",
         |            name = "Production",
         |            rank = 1,
         |            apimUrl = "http://localhost:15026/api-hub-apim-stubs",
         |            clientId = "apim-stub-client-id",
         |            secret = "apim-stub-secret",
         |            useProxy = false,
         |            apimEnvironmentName = "production",
         |            isProductionLike = true,
         |            used = true
         |        },
         |        preProduction = {
         |            id = "preProduction",
         |            name = "Pre-production",
         |            rank = 2,
         |            apimUrl = "http://localhost:15026/api-hub-apim-stubs",
         |            clientId = "apim-stub-client-id",
         |            secret = "apim-stub-secret",
         |            useProxy = false,
         |            apimEnvironmentName = "preProduction",
         |            promoteTo = "production",
         |            isProductionLike = true,
         |            used = true
         |        },
         |        test = {
         |            id = "test",
         |            name = "Test",
         |            rank = 3,
         |            apimUrl = "http://localhost:15027/apim-proxy/api-hub-apim-stubs",
         |            clientId = "apim-stub-client-id",
         |            secret = "apim-stub-secret",
         |            useProxy = true,
         |            apiKey = "some-magic-key",
         |            promoteTo = "preProduction",
         |            apimEnvironmentName = "test",
         |            isProductionLike = false,
         |            used = true
         |        }
         |    },
         |    production = "production"
         |    deployTo = "test",
         |    validateIn = "preProduction"
         |}
         |""".stripMargin)))
    succeed
  }

  "only returns used environments" in {
    val hipEnvironments = new ConfigurationHipEnvironmentsImpl(Configuration(ConfigFactory.parseString(
      s"""
         |hipEnvironments = {
         |    environments = {
         |        production = {
         |            id = "production",
         |            name = "Production",
         |            rank = 1,
         |            apimUrl = "http://localhost:15026/api-hub-apim-stubs"
         |            clientId = "apim-stub-client-id",
         |            secret = "apim-stub-secret",
         |            useProxy = false,
         |            apimEnvironmentName = "production",
         |            isProductionLike = true,
         |            used = true
         |        },
         |        test = {
         |            id = "test",
         |            name = "Test",
         |            rank = 2,
         |            apimUrl = "http://localhost:15027/apim-proxy/api-hub-apim-stubs"
         |            clientId = "apim-stub-client-id",
         |            secret = "apim-stub-secret",
         |            useProxy = true,
         |            apiKey = "some-magic-key"
         |            promoteTo = "production",
         |            apimEnvironmentName = "test",
         |            isProductionLike = false,
         |            used = false
         |        }
         |    },
         |    production = "production",
         |    deployTo = "production",
         |    validateIn = "production"
         |}
         |""".stripMargin))).environments

    hipEnvironments.map(_.id) mustBe Seq("production")
  }

}
