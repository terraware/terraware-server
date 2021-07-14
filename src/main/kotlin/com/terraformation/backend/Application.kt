package com.terraformation.backend

import com.terraformation.backend.config.TerrawareServerConfig
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.info.License
import io.swagger.v3.oas.annotations.security.SecurityScheme
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties

/**
 * Stub application class. This does nothing by itself, but it is where we hang application-level
 * configuration annotations for libraries such as Spring.
 */
@OpenAPIDefinition(
    info =
        Info(
            title = "Terraware Seed Bank",
            version = "0.1-SNAPSHOT",
            description = "Local server API for seed banks",
            license = License(name = "MIT"),
        ),
    tags = [Tag(name = "SeedBankApp"), Tag(name = "DeviceManager")])
@SecurityScheme(
    name = "ApiKey",
    type = SecuritySchemeType.HTTP,
    scheme = "basic",
    description =
        "Key-based authentication for non-browser-based clients. Username is currently ignored; password should be the API key.",
)
@EnableConfigurationProperties(TerrawareServerConfig::class)
@SpringBootApplication
class Application

fun main(args: Array<String>) {
  // By default, jOOQ logs a noisy banner message at startup; disable that to keep the logs clean.
  System.setProperty("org.jooq.no-logo", "true")
  SpringApplication.run(Application::class.java, *args)
}
