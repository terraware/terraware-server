package com.terraformation.seedbank.api.admin

import com.terraformation.seedbank.api.SimpleSuccessResponsePayload
import com.terraformation.seedbank.config.PerSiteConfigUpdater
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

@Controller
@Hidden
@RequestMapping("/api/v1/admin/config")
class ConfigController(private val perSiteConfigUpdater: PerSiteConfigUpdater) {
  @PostMapping("/refresh")
  fun refresh(): SimpleSuccessResponsePayload {
    perSiteConfigUpdater.refreshConfig()
    return SimpleSuccessResponsePayload()
  }
}
