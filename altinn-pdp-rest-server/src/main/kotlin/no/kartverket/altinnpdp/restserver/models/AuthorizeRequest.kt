package no.kartverket.altinnpdp.restserver.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import no.kartverket.altinnpdp.client.ActionId
import no.kartverket.altinnpdp.client.OrganizationNumber
import no.kartverket.altinnpdp.client.ResourceId
import no.kartverket.altinnpdp.client.SystemUserId
import no.kartverket.altinnpdp.client.exception.PdpValidationException
import no.kartverket.altinnpdp.client.validation.PdpRequestValidation

@Serializable(with = AuthorizeRequest.Serializer::class)
data class AuthorizeRequest(
    val systemuserId: SystemUserId,
    val resourceId: ResourceId,
    val organizationNumber: OrganizationNumber,
    val action: ActionId,
) {
    object Serializer : KSerializer<AuthorizeRequest> {
        override val descriptor: SerialDescriptor = Raw.serializer().descriptor

        override fun deserialize(decoder: Decoder): AuthorizeRequest {
            val raw = decoder.decodeSerializableValue(Raw.serializer())
            val errors = PdpRequestValidation.validate(
                raw.systemuserId,
                raw.resourceId,
                raw.organizationNumber,
                raw.action,
            )
            if (errors.isNotEmpty()) throw PdpValidationException(errors)
            return AuthorizeRequest(
                SystemUserId.parse(raw.systemuserId!!),
                ResourceId.parse(raw.resourceId!!),
                OrganizationNumber.parse(raw.organizationNumber!!),
                ActionId.parse(raw.action!!),
            )
        }

        override fun serialize(encoder: Encoder, value: AuthorizeRequest) {
            encoder.encodeSerializableValue(
                Raw.serializer(),
                Raw(
                    value.systemuserId.value,
                    value.resourceId.value,
                    value.organizationNumber.value,
                    value.action.value,
                ),
            )
        }
    }

    @Serializable
    @SerialName("AuthorizeRequest")
    private data class Raw(
        val systemuserId: String? = null,
        val resourceId: String? = null,
        val organizationNumber: String? = null,
        val action: String? = null,
    )
}
