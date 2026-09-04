import java.time.Instant
import kotlinx.coroutines.runBlocking
import no.kartverket.altinn.pdp.auth.AccessToken
import no.kartverket.altinn.pdp.auth.AltinnTokenProvider
import no.kartverket.altinn.pdp.client.PdpClient

fun main() = runBlocking {
	val pdpClient = PdpClient(
		platformBaseUrl = System.getenv("PDP_BASE_URL") ?: "http://localhost:8080",
		tokenProvider = object : AltinnTokenProvider {
			override suspend fun getAltinnToken() = AccessToken("dummy-token", Instant.now().plusSeconds(60))
		},
		subscriptionKey = System.getenv("PDP_SUBSCRIPTION_KEY") ?: "dummy-subscription-key",
	)

	println("PDP client initialized: $pdpClient")

	try {
		val decision = pdpClient.authorize(
			systemuserId = "su-1",
			resourceId = "test-resource",
			organizationNumber = "923609016",
			action = "read",
		)

		println("PDP decision: $decision")
	} catch (error: Exception) {
		println("Error authorizing: ${error.message}")
	}
}
