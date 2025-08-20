package com.terraformation.backend

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.config.TerrawareServerConfig.AtlassianConfig
import com.terraformation.backend.config.TerrawareServerConfig.DropboxConfig
import com.terraformation.backend.config.TerrawareServerConfig.GeoServerConfig
import com.terraformation.backend.config.TerrawareServerConfig.KeycloakConfig
import com.terraformation.backend.config.TerrawareServerConfig.MapboxConfig
import java.net.URI

fun dummyTerrawareServerConfig(
    atlassian: AtlassianConfig = AtlassianConfig(),
    dropbox: DropboxConfig = DropboxConfig(),
    geoServer: GeoServerConfig = GeoServerConfig(),
    keycloak: KeycloakConfig =
        KeycloakConfig(
            apiClientId = "dummy",
            apiClientGroupName = "dummy",
            apiClientUsernamePrefix = "dummy",
        ),
    mapbox: MapboxConfig = MapboxConfig(),
    webAppUrl: URI = URI.create("https://terraware.io"),
): TerrawareServerConfig {
  return TerrawareServerConfig(
      atlassian = atlassian,
      dropbox = dropbox,
      geoServer = geoServer,
      keycloak = keycloak,
      mapbox = mapbox,
      webAppUrl = webAppUrl,
  )
}
