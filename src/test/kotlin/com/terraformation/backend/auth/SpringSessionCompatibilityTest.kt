package com.terraformation.backend.auth

import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Verifies that the current version of the code can deserialize existing login session data. This
 * would typically only be expected to fail when we upgrade Spring. (But not all Spring upgrades
 * involve changes to the session format.)
 *
 * If this fails, it means existing login sessions will be invalid when the code is deployed.
 * Clients that are trying to use existing sessions will have to log in again. That includes the
 * frontend test suite, which uses a canned session object and doesn't know how to log in.
 *
 * What to do if this fails:
 * 1. Run `./gradlew generateFrontEndTestSession` to generate session data using the current code
 *    base.
 * 2. Replace the value of [hexEncodedSecurityContext] with the output of that command.
 * 3. Edit `dump/session.sql` in the terraware-web repo. Replace the existing hex string in the
 *    `COPY public.spring_session_attributes` statement with the new one. (Keep the `\\x` prefix,
 *    though.)
 * 4. Merge that edit into terraware-web right away after you've merged the server-side change, so
 *    the frontend test suite won't be broken for everyone.
 */
class SpringSessionCompatibilityTest {
  private val hexEncodedSecurityContext =
      "aced00057372003d6f72672e737072696e676672616d65776f726b2e73656375726974792e636f72652e636f6e746578742e5365637572697479436f6e74657874496d706c000000000000026c0200014c000e61757468656e7469636174696f6e7400324c6f72672f737072696e676672616d65776f726b2f73656375726974792f636f72652f41757468656e7469636174696f6e3b7870737200466f72672e737072696e676672616d65776f726b2e73656375726974792e61757468656e7469636174696f6e2e54657374696e6741757468656e7469636174696f6e546f6b656e00000000000000010200024c000b63726564656e7469616c737400124c6a6176612f6c616e672f4f626a6563743b4c00097072696e636970616c71007e0004787200476f72672e737072696e676672616d65776f726b2e73656375726974792e61757468656e7469636174696f6e2e416273747261637441757468656e7469636174696f6e546f6b656ed3aa287e6e47640e0200035a000d61757468656e746963617465644c000b617574686f7269746965737400164c6a6176612f7574696c2f436f6c6c656374696f6e3b4c000764657461696c7371007e00047870017372001f6a6176612e7574696c2e436f6c6c656374696f6e7324456d7074794c6973747ab817b43ca79ede020000787070740004746573747372002f636f6d2e7465727261666f726d6174696f6e2e6261636b656e642e617574682e53696d706c655072696e636970616ca43481deb93569620200014c00046e616d657400124c6a6176612f6c616e672f537472696e673b787074002430643034353235632d373933332d346365632d393634372d376236616332363432383338"

  @Test
  fun `can deserialize security context used by frontend test suite`() {
    val binary = hexEncodedSecurityContext.hexToByteArray()

    assertDoesNotThrow("Serialized session format has changed! See class docs for instructions.") {
      ObjectInputStream(ByteArrayInputStream(binary)).readObject()!!
    }
  }
}
