package no.kartverket.altinnpdp.restserver

import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.kartverket.altinnpdp.client.PdpDecision
import no.kartverket.altinnpdp.client.validation.PdpRequestValidation
import no.kartverket.altinnpdp.client.validation.PdpValidationCode
import no.kartverket.altinnpdp.restserver.models.AuthorizeRequest
import no.kartverket.altinnpdp.restserver.models.AuthorizeResponse
import no.kartverket.altinnpdp.restserver.models.ErrorCode
import no.kartverket.altinnpdp.restserver.models.ErrorResponse
import no.kartverket.altinnpdp.restserver.models.FieldError
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenApiSpecTest {

    private val schemas: JsonObject = checkNotNull(javaClass.classLoader.getResourceAsStream("documentation.json"))
        .bufferedReader()
        .use { Json.parseToJsonElement(it.readText()) }
        .jsonObject.getValue("components").jsonObject.getValue("schemas").jsonObject

    private fun properties(schema: String): JsonObject =
        schemas.getValue(schema).jsonObject.getValue("properties").jsonObject

    private fun pattern(schema: String, field: String): String =
        properties(schema).getValue(field).jsonObject.getValue("pattern").jsonPrimitive.content

    private fun allowedValues(schema: String, field: String): Set<String> =
        properties(schema).getValue(field).jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet()

    @Test
    fun `the field patterns in the spec are the ones the validation uses`() {
        assertEquals(PdpRequestValidation.UUID_FORMAT.pattern, pattern("AuthorizeRequest", "systemuserId"))
        assertEquals(PdpRequestValidation.RESOURCE_ID_FORMAT.pattern, pattern("AuthorizeRequest", "resourceId"))
        assertEquals(
            PdpRequestValidation.ORGANIZATION_NUMBER_FORMAT.pattern,
            pattern("AuthorizeRequest", "customerOrganizationNumber"),
        )
    }

    @Test
    fun `the spec lists every decision the API can return`() {
        assertEquals(PdpDecision.entries.map { it.name }.toSet(), allowedValues("AuthorizeResponse", "decision"))
    }

    @Test
    fun `the spec lists every error code the API can return`() {
        assertEquals(ErrorCode.entries.map { it.name }.toSet(), allowedValues("ErrorResponse", "code"))
    }

    @Test
    fun `the spec lists every field-level validation code`() {
        assertEquals(PdpValidationCode.entries.map { it.name }.toSet(), allowedValues("FieldError", "code"))
    }

    @Test
    fun `every schema in the spec has the same fields as the class it describes`() {
        val classes = mapOf(
            "AuthorizeRequest" to AuthorizeRequest.serializer().descriptor,
            "AuthorizeResponse" to AuthorizeResponse.serializer().descriptor,
            "ErrorResponse" to ErrorResponse.serializer().descriptor,
            "FieldError" to FieldError.serializer().descriptor,
        )

        assertEquals(schemas.keys, classes.keys, "schemas in the spec")
        for ((schema, descriptor) in classes) {
            assertEquals(descriptor.elementNames.toSet(), properties(schema).keys, "fields of $schema")
        }
    }
}
