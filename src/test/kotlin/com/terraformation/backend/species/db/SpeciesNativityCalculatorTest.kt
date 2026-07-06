package com.terraformation.backend.species.db

import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.ExternalDatasetType
import com.terraformation.backend.db.default_schema.SpeciesNativity
import com.terraformation.backend.species.model.SourcedSpeciesNativity
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpeciesNativityCalculatorTest : DatabaseTest() {
  private val calculator: SpeciesNativityCalculator by lazy {
    SpeciesNativityCalculator(dslContext)
  }

  @Test
  fun `calculates nativity from GRIIS and WCVP data`() {
    // Species is listed in two GRIIS resources, one with Invasive and one with Introduced;
    // Invasive should take precedence.
    val invasiveName = "Acceleratti incredibilis"

    // Species is listed in GRIIS (as Introduced) but not WCVP.
    val introducedName = "Birdibus zippibus"

    // Species is listed in GRIIS (as Unknown) and WCVP (as Native).
    val nativeName = "Cantus stopus"

    // Species is not listed in either GRIIS or WCVP for the target country, but is listed for
    // a different country.
    val unknownName = "Dogius ignoramii"

    val botanicalCountryCode = insertBotanicalCountry()
    val otherBotanicalCountryCode = insertBotanicalCountry()
    val countryCode = "KE"
    val otherCountryCode = "TZ"
    val griisPublicationDate = LocalDate.of(2026, 1, 1)
    val wcvpPublicationDate = LocalDate.of(2026, 2, 2)

    insertExternalDatasetImport(
        type = ExternalDatasetType.GRIIS,
        lastPublicationDate = griisPublicationDate,
    )
    insertExternalDatasetImport(
        type = ExternalDatasetType.WCVP,
        lastPublicationDate = wcvpPublicationDate,
    )

    insertGriisResource(publicationDate = griisPublicationDate, countryCode = countryCode)
    insertGriisTaxon(scientificName = invasiveName, speciesNativity = SpeciesNativity.Introduced)
    insertGriisTaxon(scientificName = introducedName, speciesNativity = SpeciesNativity.Introduced)
    insertGriisTaxon(scientificName = nativeName, speciesNativity = SpeciesNativity.Unknown)

    val otherGriisPublicationDate = LocalDate.of(2020, 3, 3)
    insertGriisResource(countryCode = countryCode, publicationDate = otherGriisPublicationDate)
    insertGriisTaxon(scientificName = invasiveName, isInvasive = true)

    insertWcvpTaxon(scientificName = invasiveName)
    insertWcvpDistribution(
        botanicalCountryCode = botanicalCountryCode,
        speciesNativity = SpeciesNativity.Introduced,
    )
    insertWcvpTaxon(scientificName = nativeName)
    insertWcvpDistribution(
        botanicalCountryCode = botanicalCountryCode,
        speciesNativity = SpeciesNativity.Native,
    )

    insertGriisResource(countryCode = otherCountryCode)
    insertGriisTaxon(scientificName = unknownName, isInvasive = true)
    insertWcvpTaxon(scientificName = unknownName)
    insertWcvpDistribution(
        botanicalCountryCode = otherBotanicalCountryCode,
        speciesNativity = SpeciesNativity.Introduced,
    )

    assertEquals(
        mapOf(
            introducedName to
                SourcedSpeciesNativity(
                    SpeciesNativity.Introduced,
                    ExternalDatasetType.GRIIS,
                    griisPublicationDate,
                ),
            invasiveName to
                SourcedSpeciesNativity(
                    SpeciesNativity.Invasive,
                    ExternalDatasetType.GRIIS,
                    griisPublicationDate,
                ),
            nativeName to
                SourcedSpeciesNativity(
                    SpeciesNativity.Native,
                    ExternalDatasetType.WCVP,
                    wcvpPublicationDate,
                ),
            unknownName to
                SourcedSpeciesNativity(
                    SpeciesNativity.Unknown,
                    null,
                    null,
                ),
        ),
        calculator.calculateNativities(
            botanicalCountryCode,
            countryCode,
            listOf(
                introducedName,
                invasiveName,
                nativeName,
                unknownName,
            ),
        ),
    )
  }
}
