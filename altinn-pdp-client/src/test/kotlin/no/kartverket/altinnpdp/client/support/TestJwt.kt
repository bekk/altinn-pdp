package no.kartverket.altinnpdp.client.support

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Instant
import java.util.Date

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
