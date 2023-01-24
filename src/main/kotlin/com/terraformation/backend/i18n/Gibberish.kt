import java.util.*

/**
 * Converts an English string to gibberish for localization testing:
 * 1. Reverse the order of words
 * 2. Replace each word with the base64 encoding of its UTF-8 representation, minus padding
 *
 * Note that this function does not preserve placeholder tokens.
 */
fun String.toGibberish(): String {
  val encoder = Base64.getEncoder()

  return split(' ').asReversed().joinToString(" ") { word ->
    encoder.encodeToString(word.toByteArray()).trimEnd('=')
  }
}
