package no.kartverket.altinnpdp.client.auth

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import no.kartverket.altinnpdp.client.exception.MaskinportenException
import java.text.ParseException

class MaskinportenKey private constructor(internal val rsaKey: RSAKey) {
    companion object {
        fun parse(jwk: String): MaskinportenKey {
            val parsed = try {
                JWK.parse(jwk)
            } catch (e: ParseException) {
                throw MaskinportenException("Failed to parse the JWK: ${e.message}", cause = e)
            }
            if (parsed !is RSAKey) {
                throw MaskinportenException("Maskinporten requires an RSA key, but the JWK is of type ${parsed.keyType}")
            }
            if (!parsed.isPrivate) {
                throw MaskinportenException("The JWK has no private key material and cannot sign")
            }
            return MaskinportenKey(parsed)
        }

        fun parseOrNull(jwk: String): MaskinportenKey? = try {
            parse(jwk)
        } catch (e: MaskinportenException) {
            null
        }
    }
}
