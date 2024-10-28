package com.terraformation.backend

import com.terraformation.backend.gis.CountryDetector

/*
 * Objects that should only be instantiated once per test suite, rather than once per test.
 *
 * Typically this is used for objects that have high initialization cost.
 *
 * All objects declared here must be thread-safe.
 */
object TestSingletons {
  val countryDetector: CountryDetector by lazy { CountryDetector() }
}
