package com.terraformation.backend.db

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.IDENTIFIER_SEQUENCES
import com.terraformation.backend.seedbank.db.AccessionStore
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * Generates user-facing identifiers. These are used in places where we need a unique value to
 * identify a resource but it's not acceptable to display the underlying integer ID from the
 * database, e.g., accession numbers.
 *
 * Identifiers are mostly-fixed-length numeric values of the form YYYYMMDDXXX where XXX is a numeric
 * suffix of three or more digits that starts at 000 for the first identifier created on a
 * particular date. The desired behavior is for the suffix to represent the order in which entries
 * were added to the system, so ideally we want to avoid gaps or out-of-order values, though it's
 * fine for that to be best-effort.
 *
 * In most cases, there should be fewer than 1000 items created by an organization on a given day,
 * so the identifiers will all be the same length, but if an organization creates a big burst of
 * data all at once, we let the identifiers grow by a digit. That is, the identifier after
 * 20221025999 is 202210251000 (999 -> 1000).
 *
 * The implementation uses a database table that holds the next value for each organization. The
 * values follow the same pattern as the identifiers, but the suffix is always 10 digits; it is
 * rendered as a 3-or-more-digit value. Doing it that way means the SQL query that allocates the
 * next value doesn't have to be aware of the "grow the identifier if the suffix overflows 3 digits"
 * logic; it just adds 1 to the previous value. To take the above example, the value in the database
 * would go from 202210250000000999 to 202210250000001000.
 *
 * If the date part of the sequence value doesn't match the current date, the value is reset to the
 * zero suffix for the current date. That is, if the database says the next value is
 * 202210310000000036 but it is now November 1, the value will be bumped to 202211010000000000 and
 * the next identifier (with the suffix collapsed to 3 digits) will be 20221101000.
 *
 * Note that although this class is guaranteed to only ever return a given value once for a given
 * organization ID, it is possible for the generated identifiers to collide with user-supplied
 * identifiers. To guard against that, [AccessionStore.create] will retry a few times if it gets an
 * identifier that's already in use.
 */
@ManagedBean
class IdentifierGenerator(
    private val clock: Clock,
    private val dslContext: DSLContext,
) {
  /**
   * Generates a new identifier for an organization.
   *
   * @param timeZone Time zone to use to determine the current date.
   */
  fun generateIdentifier(
      organizationId: OrganizationId,
      timeZone: ZoneId = ZoneOffset.UTC,
  ): String {
    val suffixMultiplier = 10000000000L
    val todayAsLong =
        LocalDate.ofInstant(clock.instant(), timeZone)
            .format(DateTimeFormatter.BASIC_ISO_DATE) // "20221031"
            .toLong()
    val firstValueForToday = todayAsLong * suffixMultiplier

    val sequenceValue =
        with(IDENTIFIER_SEQUENCES) {
          dslContext
              .insertInto(IDENTIFIER_SEQUENCES)
              .set(ORGANIZATION_ID, organizationId)
              .set(NEXT_VALUE, firstValueForToday)
              .onDuplicateKeyUpdate()
              .set(NEXT_VALUE, DSL.greatest(NEXT_VALUE.plus(1), DSL.value(firstValueForToday)))
              .returning(NEXT_VALUE)
              .fetchOne(NEXT_VALUE)!!
        }

    val datePart = sequenceValue / suffixMultiplier
    val suffixPart = sequenceValue.rem(suffixMultiplier)

    return "%08d%03d".format(datePart, suffixPart)
  }
}
