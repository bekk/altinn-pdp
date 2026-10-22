package no.kartverket.altinnpdp.client.support

import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import no.kartverket.altinnpdp.client.auth.MaskinportenKey

internal object TestKeys {
    val rsa: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
}

public val testJwk: String = TestKeys.rsa.toJSONString()

public val testMaskinportenKey: MaskinportenKey = MaskinportenKey.parse(testJwk)
