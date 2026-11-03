package no.kartverket.altinnpdp.client.auth

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import no.kartverket.altinnpdp.client.exception.MaskinportenException
import no.kartverket.altinnpdp.client.support.TestKeys
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class MaskinportenKeyTest {

    private val unusable = mapOf(
        "a key that is not RSA" to (ECKeyGenerator(Curve.P_256).keyID("ec-key").generate().toJSONString() to "RSA"),
        "a key with no private material" to (TestKeys.rsa.toPublicJWK().toJSONString() to "private key"),
        "a key with no kid" to (RSAKey.Builder(TestKeys.rsa).keyID(null).build().toJSONString() to "kid"),
        "a key with a blank kid" to (RSAKey.Builder(TestKeys.rsa).keyID(" ").build().toJSONString() to "kid"),
        "a string that is not a JWK" to ("not-a-jwk" to "parse"),
    )

    @Test
    fun `parse accepts a private RSA key`() {
        assertNotNull(MaskinportenKey.parse(TestKeys.rsa.toJSONString()))
    }

    @Test
    fun `parse refuses a key it cannot sign with, and says why`() {
        for ((why, case) in unusable) {
            val (jwk, expectedInMessage) = case

            val e = assertFailsWith<MaskinportenException>(why) { MaskinportenKey.parse(jwk) }

            assertContains(e.message!!, expectedInMessage, message = "for $why")
        }
    }
}
