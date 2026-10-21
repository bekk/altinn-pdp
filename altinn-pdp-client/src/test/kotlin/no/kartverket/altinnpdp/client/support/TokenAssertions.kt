package no.kartverket.altinnpdp.client.support

import kotlin.test.assertFalse

/** Checks [error] and every cause, since a logger prints the whole chain. */
internal fun assertNoTokenIn(error: Throwable, token: String) {
    generateSequence(error) { it.cause }.forEach {
        assertFalse(token in it.toString(), "the token must not be in ${it::class.simpleName}")
    }
    assertFalse(token in error.stackTraceToString(), "the token must not be in the stack trace")
}
