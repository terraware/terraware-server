package com.terraformation.backend.config

import com.terraformation.backend.db.FacilityId
import javax.annotation.ManagedBean
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding
import org.springframework.core.convert.converter.Converter

/**
 * Converts integer configuration values to facility IDs.
 *
 * This is only needed while TerrawareServerConfig has a facility ID config option; once that's
 * removed, this converter can go away.
 */
@ConfigurationPropertiesBinding
@ManagedBean
class FacilityIdConfigConverter : Converter<Int, FacilityId> {
  override fun convert(source: Int): FacilityId {
    return FacilityId(source.toLong())
  }
}
