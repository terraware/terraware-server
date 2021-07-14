package com.terraformation.backend.admin.api

import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.config.PerSiteConfigUpdater
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
