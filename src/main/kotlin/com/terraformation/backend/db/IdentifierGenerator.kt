package com.terraformation.backend.db

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.IDENTIFIER_SEQUENCES
import com.terraformation.backend.seedbank.db.AccessionStore
import jakarta.inject.Named
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.jooq.DSLContext

/**
 * Generates user-facing identifiers. These are used in places where we need a unique value to
 * identify a resource but it's not acceptable to display the underlying integer ID from the
 * database, e.g., accession numbers.
 *
 * Identifiers come in two flavors: text and numeric.
 *
 * # Text identifiers
 *
 * Text identifiers are mostly-fixed-length values of the form `YY-T-F-XXX` where:
 * - `YY` is the two-digit year
 * - `T` is a digit indicating the type of identifier; see [IdentifierType]
 * - `F` is a facility number that starts at 1 for each facility type in an organization
 * - `XXX` is a sequence number that starts at 1 and goes up by 1 for each identifier of a
 *   particular type in an organization, zero-padded so it is at least 3 digits
 *
 * The desired behavior is for the `XXX` part to represent the order in which entries were added to
 * the system, so ideally we want to avoid gaps or out-of-order values, though it's fine for that to
 * be best-effort.
 *
 * In the highly unlikely event this code still exists in the year 2122, the two-digit year from
 * 2022 will be reused, but this won't cause a collision; the next sequence value from 2022 will
 * still exist and will be used.
 *
 * The implementation uses a database table that holds the next value of `XXX` for each
 * (organization, year, type) combination. Facility number isn't included because we want the `XXX`
 * values to be unique across facilities of a given type.
 *
 * Note that although this class is guaranteed to only ever return a given value once for a given
 * organization ID, it is possible for the generated identifiers to collide with user-supplied
 * identifiers. To guard against that, [AccessionStore.create] will retry a few times if it gets an
 * identifier that's already in use.
 *
 * # Numeric identifiers
 *
 * Numeric identifiers are sequential [Long] values that start at 1 for a given organization and
 * identifier type. Unlike text identifiers, numeric identifiers don't reset at the start of the
 * year and don't include any facility information.
 *
 * The identifier types for numeric identifiers are distinct from the ones for text identifiers and
 * are listed in [NumericIdentifierType].
 */
@Named
class IdentifierGenerator(
    private val clock: Clock,
    private val dslContext: DSLContext,
) {
  /**
   * Generates a new text identifier for an organization.
   *
   * @param timeZone Time zone to use to determine the current date.
   */
  fun generateTextIdentifier(
      organizationId: OrganizationId,
      identifierType: IdentifierType,
      facilityNumber: Int,
      timeZone: ZoneId = ZoneOffset.UTC,
  ): String {
    val shortYear = LocalDate.ofInstant(clock.instant(), timeZone).year.rem(100)
    val prefix = "%02d-%c-".format(shortYear, identifierType.digit)

    val sequenceValue = getNextValue(organizationId, prefix)

    return "%s%d-%03d".format(prefix, facilityNumber, sequenceValue)
  }

  /** Generates a new numeric identifier for an organization. */
  fun generateNumericIdentifier(organizationId: OrganizationId, type: NumericIdentifierType): Long {
    return getNextValue(organizationId, type.name)
  }

  private fun getNextValue(organizationId: OrganizationId, prefix: String): Long {
    return with(IDENTIFIER_SEQUENCES) {
      dslContext
          .insertInto(IDENTIFIER_SEQUENCES)
          .set(ORGANIZATION_ID, organizationId)
          .set(PREFIX, prefix)
          .set(NEXT_VALUE, 1)
          .onDuplicateKeyUpdate()
          .set(NEXT_VALUE, NEXT_VALUE.plus(1))
          .returning(NEXT_VALUE)
          .fetchOne(NEXT_VALUE)!!
    }
  }

  /**
   * Replaces the facility number in an existing identifier with a new one. Returns null if the
   * existing identifier isn't in the correct format (e.g., because it was supplied by a user).
   */
  fun replaceFacilityNumber(identifier: String, newFacilityNumber: Int): String? {
    val matches = Regex("(\\d\\d-\\d-)\\d+(-\\d+)").matchEntire(identifier) ?: return null

    return "${matches.groupValues[1]}$newFacilityNumber${matches.groupValues[2]}"
  }
}

enum class IdentifierType(val digit: Char) {
  ACCESSION('1'),
  BATCH('2'),
}

enum class NumericIdentifierType {
  PlotNumber
}
