package com.terraformation.backend.tracking.edit

/**
 * Returns a list of elements pulled from two sources with a specified proportion of elements coming
 * from each source. This is done in a round-robin-style fashion that attempts to spread each
 * source's elements as evenly as possible across the returned list.
 *
 * For example, if the number of elements is 13 and the fraction from the first source is 0.75, the
 * result would be `FFFSFFFSFFFSF` (where F and S mean the first and second sources).
 *
 * @param numElements Total number of elements to pull from both sources combined. The returned list
 *   will always have this many elements.
 * @param fractionFromFirstSource Value between 0 and 1 specifying how many elements should come
 *   from the first source.
 * @param firstSource Returns an element from the first source. Its parameter is the index in the
 *   combined list.
 * @param secondSource Returns an element from the second source. Its parameter is the index in the
 *   combined list.
 */
fun <T> zipProportionally(
    numElements: Int,
    fractionFromFirstSource: Double,
    firstSource: (Int) -> T,
    secondSource: (Int) -> T,
): List<T> {
  if (fractionFromFirstSource < 0.0 || fractionFromFirstSource > 1.0) {
    throw IllegalArgumentException("Fraction must be between 0 and 1")
  }
  if (numElements < 0) {
    throw IllegalArgumentException("Number of elements must not be negative")
  }

  // This works by awarding "credits" to each source when the OTHER source is chosen, and picking
  // whichever source currently has the most credits. The less-chosen source will eventually build
  // up enough credits to be chosen next.

  val fractionFromSecondSource = 1.0 - fractionFromFirstSource
  var firstSourceCredits = fractionFromFirstSource
  var secondSourceCredits = fractionFromSecondSource

  return List(numElements) { index ->
    if (firstSourceCredits >= secondSourceCredits) {
      secondSourceCredits += fractionFromSecondSource
      firstSource(index)
    } else {
      firstSourceCredits += fractionFromFirstSource
      secondSource(index)
    }
  }
}
