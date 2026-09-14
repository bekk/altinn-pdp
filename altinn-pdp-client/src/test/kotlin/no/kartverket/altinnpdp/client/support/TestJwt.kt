package no.kartverket.altinnpdp.client.support

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Instant
import java.util.Date

/** Generating an RSA key is slow, so the whole test run shares one. */
internal object TestKeys {
    val rsa: RSAKey by lazy { RSAKeyGenerator(2048).keyID("test-key").generate() }
}

/**
 * A signed JWT shaped like the token Altinn returns from the exchange. Only `exp` matters to the
 * client, but signing it keeps the fixture honest about what is actually parsed.
 */
internal fun signedJwt(expiresAt: Instant?, issuedAt: Instant = NOW): String {
    val claims = JWTClaimsSet.Builder().issuer("https://test.altinn.no").issueTime(Date.from(issuedAt))
    expiresAt?.let { claims.expirationTime(Date.from(it)) }
    val jwt = SignedJWT(
        JWSHeader.Builder(JWSAlgorithm.RS256).keyID(TestKeys.rsa.keyID).build(),
        claims.build(),
    )
    jwt.sign(RSASSASigner(TestKeys.rsa))
    return jwt.serialize()
}
