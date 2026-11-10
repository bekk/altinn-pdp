package no.kartverket.altinnpdp.restserver.authorize

import no.bekk.bekkopen.person.FodselsnummerValidator
import no.kartverket.altinnpdp.client.ActionId
import no.kartverket.altinnpdp.client.AltinnEnvironment
import no.kartverket.altinnpdp.client.OrganizationNumber
import no.kartverket.altinnpdp.client.PdpSubject
import no.kartverket.altinnpdp.client.PersonId
import no.kartverket.altinnpdp.client.ResourceId
import no.kartverket.altinnpdp.client.SystemUserId
import no.kartverket.altinnpdp.restserver.problem.FieldError
import no.kartverket.altinnpdp.restserver.problem.ValidationCode
import no.kartverket.altinnpdp.client.ResourceAttribute as PdpResourceAttribute

internal class InvalidRequestException(val errors: List<FieldError>) : RuntimeException()

/**
 * Turns the fields of a request into the client's types. The client's own `parse` decides whether a value is valid,
 * so a request that passes here is never refused by the client with a 500. This adds what the client cannot know: the
 * field and the reason for each error, every error at once, and the rules that are the server's own. The reasons
 * describe the client's rules in words, so a test checks that the client accepts the values they allow.
 */
internal object PdpRequestValidation {

    private val PID_FORMAT = Regex(PersonId.PATTERN)

    private val ORGANIZATION_NUMBER_FORMAT = Regex(OrganizationNumber.PATTERN)

    private val RESOURCE_ATTRIBUTE_TYPE_FORMAT = Regex(PdpResourceAttribute.TYPE_PATTERN)

    fun parse(
        systemuserId: String?,
        pid: String?,
        resourceId: String?,
        customerOrganizationNumber: String?,
        action: String?,
        resourceAttributes: List<Pair<String?, String?>>,
    ): AuthorizeRequest.Parsed {
        val errors = mutableListOf<FieldError>()
        val subject = errors.subject(systemuserId, pid)
        val parsedResourceId = errors.parseField("resourceId", resourceId, ResourceId::parse) {
            "must be at least 4 characters of lowercase letters, digits, underscore or hyphen"
        }
        val organizationNumber = errors.parseField("customerOrganizationNumber", customerOrganizationNumber, OrganizationNumber::parse) {
            if (it.matches(ORGANIZATION_NUMBER_FORMAT)) "must have a valid MOD11 check digit" else "must be exactly 9 digits"
        }
        val parsedAction = errors.parseField("action", action, ActionId::parse) { "is not valid" }
        val attributes = errors.resourceAttributes(resourceAttributes)
        if (errors.isNotEmpty()) throw InvalidRequestException(errors)
        return AuthorizeRequest.Parsed(subject!!, parsedResourceId!!, organizationNumber!!, parsedAction!!, attributes.sortedBy { it.type })
    }

    private fun MutableList<FieldError>.subject(systemuserId: String?, pid: String?): PdpSubject? {
        val hasSystemuserId = !systemuserId.isNullOrBlank()
        val hasPid = !pid.isNullOrBlank()
        return when {
            hasSystemuserId && hasPid -> {
                addToBothSubjectFields(ValidationCode.CONFLICTING, "send systemuserId or pid, not both")
                null
            }

            hasSystemuserId -> parseField("systemuserId", systemuserId, SystemUserId::parse) { "must be a UUID" }

            hasPid -> parseField("pid", pid, ::personId) {
                if (it.matches(PID_FORMAT)) "must be a valid fødselsnummer or D number" else "must be exactly 11 digits"
            }

            else -> {
                addToBothSubjectFields(ValidationCode.MISSING, "systemuserId or pid is required")
                null
            }
        }
    }

    private fun MutableList<FieldError>.addToBothSubjectFields(code: ValidationCode, message: String) {
        listOf("systemuserId", "pid").mapTo(this) { FieldError(it, code, message) }
    }

    /** The client only checks the length, since whether synthetic persons pass is the server's choice */
    private fun personId(value: String): PersonId {
        val personId = PersonId.parse(value)
        require(FodselsnummerValidator.isValid(value)) { "Expected a valid fødselsnummer or D number" }
        return personId
    }

    private fun MutableList<FieldError>.resourceAttributes(typesAndIds: List<Pair<String?, String?>>): List<PdpResourceAttribute> {
        val repeatedTypes = typesAndIds.mapNotNull { it.first }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return typesAndIds.mapIndexedNotNull { index, (type, id) ->
            val field = "resourceAttributes[$index]"
            val parsedType = parseField("$field.type", type, PdpResourceAttribute::requireValidType) {
                if (it.matches(RESOURCE_ATTRIBUTE_TYPE_FORMAT)) {
                    "must not start with urn:altinn:, other than urn:altinn:subresource"
                } else {
                    "must be urn:<name> or urn:<name>:<name>, with names of lowercase letters, hyphen or underscore"
                }
            }
            if (parsedType in repeatedTypes) add(FieldError("$field.type", ValidationCode.CONFLICTING, "send each type only once"))
            val parsedId = parseField("$field.id", id, PdpResourceAttribute::requireValidId) { "must not contain a colon" }
            if (parsedType == null || parsedId == null) {
                null
            } else {
                // Only a rule on the type and the id together can refuse them here, and the client has none yet
                orInvalid(field, { "must have a type and an id that are valid together" }) {
                    PdpResourceAttribute.parse(parsedType, parsedId)
                }
            }
        }
    }

    /**
     * Parses [value] with the client's [parse], or adds an error for [field] and gives null: MISSING when the value is
     * absent or blank, and INVALID_FORMAT with the reason from [invalid] when the client refuses it.
     */
    private inline fun <T : Any> MutableList<FieldError>.parseField(
        field: String,
        value: String?,
        parse: (String) -> T,
        invalid: (String) -> String,
    ): T? {
        if (value.isNullOrBlank()) {
            add(FieldError(field, ValidationCode.MISSING, "$field is required"))
            return null
        }
        return orInvalid(field, { invalid(value) }) { parse(value) }
    }

    /** Gives what [parse] gives, or adds an INVALID_FORMAT error for [field] and gives null when the client refuses it */
    private inline fun <T : Any> MutableList<FieldError>.orInvalid(field: String, reason: () -> String, parse: () -> T): T? =
        try {
            parse()
        } catch (e: IllegalArgumentException) {
            add(FieldError(field, ValidationCode.INVALID_FORMAT, "$field ${reason()}"))
            null
        }
}

internal fun configurePidValidation(environment: AltinnEnvironment) {
    FodselsnummerValidator.ALLOW_SYNTHETIC_NUMBERS = environment == AltinnEnvironment.TT02
}
