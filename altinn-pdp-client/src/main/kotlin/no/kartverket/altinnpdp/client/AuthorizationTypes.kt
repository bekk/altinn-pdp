package no.kartverket.altinnpdp.client

import no.bekk.bekkopen.org.OrganisasjonsnummerValidator
import kotlin.uuid.Uuid

public sealed interface PdpSubject

@JvmInline
public value class SystemUserId private constructor(public val value: String) : PdpSubject {
    public companion object {
        public fun parse(value: String): SystemUserId {
            require(Uuid.parseHexDashOrNull(value) != null) { "Expected a systemuserId as a UUID, but was \"$value\"" }
            return SystemUserId(value)
        }
    }
}

@JvmInline
public value class PersonId private constructor(public val value: String) : PdpSubject {
    override fun toString(): String = "PersonId(***********)"

    public companion object {
        private val PID = Regex("[0-9]{11}")

        // Only the length, since NoCommons' switch for synthetic persons is global and belongs to the app
        public fun parse(value: String): PersonId {
            require(PID.matches(value)) { "Expected a pid of exactly 11 digits" }
            return PersonId(value)
        }
    }
}

@JvmInline
public value class ResourceId private constructor(public val value: String) {
    public companion object {
        private val RESOURCE_ID = Regex("[a-z0-9_-]{4,}")

        public fun parse(value: String): ResourceId {
            require(RESOURCE_ID.matches(value)) {
                "Expected a resourceId of 4 or more of a-z, 0-9, _ and -, but was \"$value\""
            }
            return ResourceId(value)
        }
    }
}

@JvmInline
public value class OrganizationNumber private constructor(public val value: String) {
    public companion object {
        private val ORGANIZATION_NUMBER = Regex("[0-9]{9}")

        public fun parse(value: String): OrganizationNumber {
            require(ORGANIZATION_NUMBER.matches(value) && OrganisasjonsnummerValidator.isValid(value)) {
                "Expected an organisation number of 9 digits with a valid MOD11 check digit, but was \"$value\""
            }
            return OrganizationNumber(value)
        }
    }
}

@JvmInline
public value class ActionId private constructor(public val value: String) {
    public companion object {
        public fun parse(value: String): ActionId {
            require(value.isNotBlank()) { "Expected an action that is not blank" }
            return ActionId(value)
        }
    }
}
