package com.terraformation.backend

import com.terraformation.backend.config.TerrawareServerConfig
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.info.License
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.security.SecurityScheme
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Stub application class. This does nothing by itself, but it is where we hang application-level
 * configuration annotations for libraries such as Spring.
 */
@OpenAPIDefinition(
    info =
        Info(
            title = "Terraware Server",
            version = VERSION,
            description = "Back end for Terraware applications",
            license =
                License(
                    name = "Apache 2.0",
                    url = "https://www.apache.org/licenses/LICENSE-2.0.html",
                ),
        ),
    security =
        [
            SecurityRequirement(name = "cookie"),
            SecurityRequirement(
                name = "openId",
                scopes = ["email", "offline_access", "openid", "profile"],
            ),
        ],
)
@SecurityScheme(
    type = SecuritySchemeType.APIKEY,
    name = "cookie",
    paramName = "SESSION",
    `in` = SecuritySchemeIn.COOKIE,
    description = "Session cookie",
)
@EnableConfigurationProperties(TerrawareServerConfig::class)
@EnableScheduling
@SpringBootApplication(nameGenerator = FullyQualifiedAnnotationBeanNameGenerator::class)
class Application

fun main(args: Array<String>) {
  // By default, jOOQ logs a noisy banner message at startup; disable that to keep the logs clean.
  System.setProperty("org.jooq.no-logo", "true")

  // Make sure the system property to allow registering custom locale providers is set. Annoyingly,
  // it is not possible to change this programmatically; it has to be a command-line argument.
  val expectedProviders = "SPI,CLDR"
  if (System.getProperty("java.locale.providers") != expectedProviders) {
    throw RuntimeException(
        "Please add -Djava.locale.providers=$expectedProviders to the JVM's command-line arguments."
    )
  }

  SpringApplication.run(Application::class.java, *args)
}
