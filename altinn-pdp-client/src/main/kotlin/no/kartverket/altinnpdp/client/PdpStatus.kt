package no.kartverket.altinnpdp.client

public sealed interface PdpStatus {
    public val value: String

    public data object Ok : PdpStatus {
        override val value: String = "urn:oasis:names:tc:xacml:1.0:status:ok"
    }

    public data object ProcessingError : PdpStatus {
        override val value: String = "urn:oasis:names:tc:xacml:1.0:status:processing-error"
    }

    public data class Other(override val value: String) : PdpStatus

    public companion object {
        public fun of(value: String): PdpStatus = when (value) {
            Ok.value -> Ok
            ProcessingError.value -> ProcessingError
            else -> Other(value)
        }
    }
}
