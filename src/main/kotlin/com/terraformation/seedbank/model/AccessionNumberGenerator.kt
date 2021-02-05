package com.terraformation.seedbank.model

import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.truncate
import kotlin.random.Random

class AccessionNumberGenerator {
  fun generateAccessionNumber(desiredBitsOfEntropy: Int = 40): String {
    // Use characters that are unlikely to be visually confused even when they are printed on
    // labels sitting inside plastic bags.
    val alphabet = "3479ACDEFHJKLMNPRTWY"
    val bitsPerCharacter = log2(alphabet.length.toFloat())
    val requiredLength = truncate(desiredBitsOfEntropy / bitsPerCharacter + 1).roundToInt()

    return 0.rangeTo(requiredLength)
        .map { alphabet[Random.nextInt(alphabet.length)] }
        .joinToString("")
  }
}
