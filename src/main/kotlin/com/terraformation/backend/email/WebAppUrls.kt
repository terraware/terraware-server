package com.terraformation.backend.email

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.OrganizationId
import java.net.URI
import javax.annotation.ManagedBean
import javax.ws.rs.core.UriBuilder

/**
 * Constructs URLs for specific locations in the web app. These are used in things like notification
 * email messages that need to include direct links to specific areas of the app.
 */
@ManagedBean
class WebAppUrls(private val config: TerrawareServerConfig) {
  fun organizationHome(organizationId: OrganizationId): URI {
    return UriBuilder.fromUri(config.webAppUrl)
        .path("/home")
        .queryParam("organizationId", organizationId)
        .build()
  }

  /** Generates a relative path of organization home within the web app */
  fun inAppOrganizationHome(organizationId: OrganizationId): URI {
    return UriBuilder.fromPath("/home").queryParam("organizationId", organizationId).build()
  }
}
