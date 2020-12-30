package com.terraformation.seedbank

import io.micronaut.runtime.Micronaut
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.info.License

@OpenAPIDefinition(
    info =
        Info(
            title = "Terraware Seed Bank",
            version = "0.1-SNAPSHOT",
            description = "Local server API for seed banks",
            license = License(name = "MIT")))
object Application {
  @JvmStatic
  fun main(args: Array<String>) {
    // By default, jOOQ logs a noisy banner message at startup; disable that to keep the logs clean.
    System.setProperty("org.jooq.no-logo", "true")

    Micronaut.build(*args)
        .defaultEnvironments("dev")
        .eagerInitConfiguration(true)
        .mainClass(Application.javaClass)
        .start()
  }
}
