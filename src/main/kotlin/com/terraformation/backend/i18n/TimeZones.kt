package com.terraformation.backend.i18n

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.squarespace.cldrengine.CLDR
import jakarta.inject.Named
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.MissingResourceException
import java.util.Optional
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap
import org.apache.commons.text.similarity.LevenshteinDistance

/**
 * Generates a list of time zones with localized names for use by clients and other parts of the
 * server.
 *
 * This is a trickier proposition than it sounds. There is no single correct approach that is the
 * best answer for all possible use cases. So we optimize for our own use case of a web app that
 * needs to show a list of human-readable time zone names and needs to convert UTC timestamps to
 * specific time zones that might not be the same as a given browser's local time zone.
 *
 * The general philosophy is to make use of the Unicode CLDR project's translations to the greatest
 * extent possible, only falling back on our own translations as a last resort.
 *
 * How we determine which zone IDs to include:
 * - We start with Google Chrome's list of supported time zones. Experimentally, we found that it
 *   supports a set of time zones that is also supported on all other major browsers. (The reverse
 *   isn't true; for example, Safari supports a couple time zones that Chrome doesn't.) This list is
 *   stored in a resource file. See [chromeZoneIds].
 * - We remove time zones that are irrelevant for our application, e.g., Antarctica, as well as time
 *   zones that are missing localized names in all our officially supported languages and where
 *   there is an equivalent time zone that can be chosen instead. See [excludePattern].
 * - We remove redundant zone IDs so the list isn't too huge to reasonably show in a user interface.
 *   For example, `Europe/Jersey` is an alias for `Europe/London` and we only include the latter.
 *   See [timeZonesWithUniqueEnglishNames].
 *
 * Where the localized zone names come from:
 * - If we have our own localized name for a zone (as defined in a resource bundle file), we use it.
 *   In practice, this is only needed in a few cases. See [getTimeZoneNameFromResourceBundle].
 * - We use the [CLDR] library, which is based on the Unicode CLDR project's data set, as the main
 *   source of localized names. [ZoneId.getDisplayName] lacks some features we need. Unfortunately,
 *   the CLDR library is also missing some things we need, so we fall back on Java in some cases.
 * - For time zones that observe daylight saving time, we use the generic form of the time zone
 *   name, e.g., "Pacific Time." For time zones that don't adjust their clocks, we use the
 *   standard-time name, e.g., "Mountain Standard Time." See [getTimeZoneNameWithoutCity].
 * - If there are multiple zone IDs that map to the same name, we append the name of the example
 *   city (as defined in the CLDR data set) to each one. For example, both Vancouver and Los Angeles
 *   are in "Pacific Time" but they aren't aliases for one another, so we render them as "Pacific
 *   Time - Vancouver" and "Pacific Time - Los Angeles." But for zone names that only map to a
 *   single zone ID, we don't add the city. See [getTimeZoneNameWithCity].
 *
 * [The Unicode CLDR project's time zone
 * formatter](https://github.com/unicode-org/cldr/blob/release-42/tools/cldr-code/src/main/java/org/unicode/cldr/util/TimezoneFormatter.java)
 * was a valuable source of inspiration for this implementation, though it makes some different
 * design choices.
 */
@Named
class TimeZones(private val messages: Messages, private val objectMapper: ObjectMapper) {
  /**
   * Matches zone IDs that are recognized by Chrome, but that we don't care about in the app or that
   * lack translations in languages we support and can be covered by other zone IDs.
   */
  private val excludePattern =
      listOf(
              // Irrelevant for our user base.
              "Antarctica/.*",
              // Missing translations in all major languages; other zones can be chosen instead.
              "Asia/Barnaul",
              "Asia/Tomsk",
              "Europe/Kirov",
          )
          .joinToString("|")
          .toRegex()

  /** Cache of names in each locale. */
  private val localizedTimeZoneNames = ConcurrentHashMap<Locale, Map<ZoneId, String>>()

  /** Returns a map of zone IDs to localized names for a particular locale. */
  fun getTimeZoneNames(locale: Locale): Map<ZoneId, String> {
    return localizedTimeZoneNames.getOrPut(locale) {
      timeZonesWithUniqueEnglishNames.associateWith { getTimeZoneNameWithCity(it, locale) }
    }
  }

  /**
   * Zone IDs supported by Chrome. This doesn't include zones that match [excludePattern] but does
   * include redundant zones we will remove later.
   */
  private val chromeZoneIds =
      javaClass
          .getResourceAsStream("/i18n/TimeZones.json")
          ?.use<InputStream, List<String>> { inputStream -> objectMapper.readValue(inputStream) }
          ?.filterNot { excludePattern.matches(it) }
          ?.map { ZoneId.of(it) }
          ?.plus(UTC) ?: throw RuntimeException("Unable to load list of time zones")

  /**
   * Returns the localized name of a time zone without a city suffix. This is not unique across our
   * list of time zones: `Europe/Paris` and `Europe/Berlin` both render as "Central European Time"
   * in English.
   *
   * If we have explicitly defined a translation in one of our resource bundles, use it.
   *
   * If the time zone has daylight saving time transitions, prefer its generic name. For example,
   * `America/Los_Angeles` should render as "Pacific Time" rather than as "Pacific Standard Time" or
   * "Pacific Daylight Time".
   *
   * If the time zone has no daylight saving time transitions, prefer its standard name. For
   * example, `America/Phoenix` (Phoenix does not observe DST) should render as "Mountain Standard
   * Time" rather than as "Mountain Time".
   *
   * If the CLDR library returns the English name rather than a localized name, fall back on Java's
   * localization. Java only knows the generic names, so this will lose the distinction between DST
   * and non-DST zone names. (In practice, the CLDR library has very good coverage and we only fall
   * back for a small handful of zones.)
   */
  private fun getTimeZoneNameWithoutCity(zoneId: ZoneId, locale: Locale): String {
    // If we have our own translation, prefer it.
    val nameFromResourceBundle = getTimeZoneNameFromResourceBundle(zoneId, locale)
    if (nameFromResourceBundle != null) {
      return nameFromResourceBundle
    }

    val hasDaylightSavingTime = zoneId.rules.nextTransition(Instant.now()) != null
    val englishZoneNames =
        CLDR.get(Locale.ENGLISH).Calendars.timeZoneInfo(zoneId.id).names().long_()

    val localZoneNames = CLDR.get(locale).Calendars.timeZoneInfo(zoneId.id).names().long_()
    val genericName = localZoneNames.generic()
    val standardName = localZoneNames.standard()

    return when {
      hasDaylightSavingTime &&
          genericName.isNotEmpty() &&
          (locale.language == "en" || genericName != englishZoneNames.generic()) -> {
        // America/Los_Angeles -> Pacific Time
        genericName
      }
      standardName.isNotEmpty() &&
          (locale.language == "en" || standardName != englishZoneNames.standard()) -> {
        // America/Phoenix -> Mountain Standard Time
        standardName
      }
      else -> {
        // CLDR doesn't have a translation, so fall back on Java
        zoneId.getDisplayName(TextStyle.FULL_STANDALONE, locale)
      }
    }
  }

  /**
   * Cache of resource bundles for [getTimeZoneNameFromResourceBundle]. The values are [Optional] so
   * we can cache the nonexistence of a bundle.
   */
  private val resourceBundles = ConcurrentHashMap<Locale, Optional<ResourceBundle>>()

  /**
   * Looks up a time zone name from a message bundle. This covers missing translations as well as
   * fixing a quirk with the localized name for "UTC", which gets rendered incorrectly as "Greenwich
   * Mean Time" in a bunch of languages.
   *
   * @return The localized name or null if there wasn't one defined.
   */
  private fun getTimeZoneNameFromResourceBundle(zoneId: ZoneId, locale: Locale): String? {
    val bundle =
        resourceBundles.getOrPut(locale) {
          try {
            Optional.of(ResourceBundle.getBundle("i18n.TimeZoneWithoutCity", locale))
          } catch (e: MissingResourceException) {
            Optional.empty()
          }
        }

    return if (bundle.isPresent && bundle.get().containsKey(zoneId.id)) {
      bundle.get().getString(zoneId.id)
    } else {
      null
    }
  }

  /** Cache of return values of [getTimeZonesWithMultipleCities]. */
  private val timeZonesWithMultipleCities = ConcurrentHashMap<Locale, Set<ZoneId>>()

  /**
   * Returns the set of zone IDs that require city names for disambiguation in a locale.
   *
   * For example, the English time zone name "Pacific Time" (as returned by
   * [getTimeZoneNameWithoutCity]) applies to both `America/Los_Angeles` and `America/Vancouver`,
   * among others. So for those zones, we need to include the city in the localized name so users
   * can tell them apart.
   *
   * The calculation here ignores aliases, by virtue of the fact that the city for an alias is the
   * city of the underlying zone. That is, `Europe/Jersey` is an alias for `Europe/London`, so its
   * city is "London". Those two zones are thus treated as representing a single city.
   */
  private fun getTimeZonesWithMultipleCities(locale: Locale): Set<ZoneId> {
    return timeZonesWithMultipleCities.getOrPut(locale) {
      val calendars = CLDR.get(locale).Calendars
      chromeZoneIds
          .groupBy { getTimeZoneNameWithoutCity(it, locale) }
          .values
          .flatMap { zoneIds ->
            val cities = zoneIds.map { calendars.timeZoneInfo(it.id).city().name() }
            if (cities.distinct().size != 1) {
              zoneIds
            } else {
              emptyList()
            }
          }
          .toSet()
    }
  }

  /**
   * Returns the localized name of a time zone including a suffix with the time zone's example city
   * name if needed for disambiguation.
   *
   * This function does double duty: it is called during class initialization as part of generating
   * the list of supported zone IDs (the locale is always English at that point) and it is also
   * called to generate the localized names in other languages.
   *
   * This can return duplicate names: `Europe/Jersey` and `Europe/London` will both be rendered as
   */
  private fun getTimeZoneNameWithCity(zoneId: ZoneId, locale: Locale): String {
    val nameWithoutCity = getTimeZoneNameWithoutCity(zoneId, locale)
    val calendars = CLDR.get(locale).Calendars
    val city = calendars.timeZoneInfo(zoneId.id).city().name()

    val fullName =
        if (zoneId in getTimeZonesWithMultipleCities(locale)) {
          // Multiple non-alias zones map to the same English name, so include the city to
          // disambiguate.
          messages.timeZoneWithCity(nameWithoutCity, city)
        } else {
          // There's only one example city for this English zone name, so no need to include it.
          nameWithoutCity
        }

    return if (locale == Locales.GIBBERISH) {
      fullName.toGibberish()
    } else {
      fullName
    }
  }

  /**
   * List of zone IDs that map to unique English names. This is the list of zone IDs we expose to
   * clients.
   *
   * When there are multiple zone IDs that map to the same English name, we need to choose which one
   * we treat as canonical: does our list of zone IDs use `Europe/Jersey` or `Europe/London`?
   *
   * We don't have a direct way of detecting aliases, but we can achieve the same effect with a
   * heuristic: pick the zone ID with the minimum Levenshtein distance to the example city name.
   *
   * For example, `Europe/Jersey` is an alias for `Europe/London` which means they both have a city
   * name of `London`. If we pick the one with the smallest edit distance, we choose the right one:
   * ```
   * distance("London", "Europe/Jersey") == 12
   * distance("London", "Europe/London") == 7
   * ```
   */
  private val timeZonesWithUniqueEnglishNames: List<ZoneId> by lazy {
    val calendar = CLDR.get(Locale.ENGLISH).Calendars
    val levenshtein = LevenshteinDistance.getDefaultInstance()
    chromeZoneIds
        .groupBy { getTimeZoneNameWithCity(it, Locale.ENGLISH) }
        .values
        .map { zones ->
          zones.minBy { zone ->
            val city = calendar.timeZoneInfo(zone.id).city().name()
            levenshtein.apply(city, zone.id)
          }
        }
  }

  companion object {
    val UTC: ZoneId = ZoneId.of("Etc/UTC")
  }
}
