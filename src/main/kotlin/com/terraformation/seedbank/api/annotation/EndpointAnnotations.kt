package com.terraformation.seedbank.api.annotation

import io.swagger.v3.oas.annotations.tags.Tag

@Retention(AnnotationRetention.RUNTIME)
@Tag(name = "SeedBankApp")
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class SeedBankAppEndpoint
