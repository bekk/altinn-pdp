package no.kartverket.altinnpdp.client.auth

@JvmInline
internal value class AccessToken private constructor(val value: String) {
    override fun toString(): String = "AccessToken(***)"

    companion object {
        fun of(value: String): AccessToken = AccessToken(value)
    }
}
