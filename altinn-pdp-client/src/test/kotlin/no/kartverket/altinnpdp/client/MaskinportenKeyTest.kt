package no.kartverket.altinnpdp.client

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import no.kartverket.altinnpdp.client.support.TestKeys
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class MaskinportenKeyTest {

    @Test
    fun `parse accepts a private RSA key`() {
        assertNotNull(MaskinportenKey.parse(TestKeys.rsa.toJSONString()))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusable")
    fun `parse refuses a key it cannot sign with, and says why`(why: String, jwk: String, expectedInMessage: String) {
        val e = assertFailsWith<MaskinportenException>(why) { MaskinportenKey.parse(jwk) }

        assertContains(e.message!!, expectedInMessage)
    }

    companion object {
        @JvmStatic
        fun unusable(): List<Arguments> = listOf(
            Arguments.of("a key that is not RSA", ECKeyGenerator(Curve.P_256).keyID("ec-key").generate().toJSONString(), "RSA"),
            Arguments.of("a key with no private material", TestKeys.rsa.toPublicJWK().toJSONString(), "private key"),
            Arguments.of("a key with no kid", RSAKey.Builder(TestKeys.rsa).keyID(null).build().toJSONString(), "kid"),
            Arguments.of("a key with a blank kid", RSAKey.Builder(TestKeys.rsa).keyID(" ").build().toJSONString(), "kid"),
            Arguments.of("a string that is not a JWK", "not-a-jwk", "parse"),
        )
    }
}
