package dev.codexremote.app.bridge

import dev.codexremote.app.protocol.CommandEnvelope
import dev.codexremote.app.protocol.DeviceCredential
import dev.codexremote.app.protocol.PairExchangeRequest
import dev.codexremote.app.protocol.PairingInvitation
import dev.codexremote.app.diagnostics.DiagnosticLogStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class BridgeHttpClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: BridgeHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = BridgeHttpClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun defaultHttpClientHasBoundedCallTimeout() {
        assertEquals(30_000, bridgeHttpOkHttpClient().callTimeoutMillis)
    }

    @Test
    fun exchangePostsPairingRequestAndDecodesCredential() {
        server.enqueue(
            jsonResponse(
                """{"protocolVersion":1,"deviceId":"phone-1","credential":"credential-1"}""",
            ),
        )
        val invitation = PairingInvitation.parse(
            "codex-remote://pair?baseUrl=${encodedBaseUrl()}&token=pair-token",
        )

        val credential = client.exchange(invitation, "phone-1", "Pixel")

        assertEquals(1, credential.protocolVersion)
        assertEquals("phone-1", credential.deviceId)
        assertEquals("credential-1", credential.credential)
        val request = server.takeRequest()
        assertEquals("/v1/pair/exchange", request.path)
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals(
            Json.parseToJsonElement(
                """{"token":"pair-token","deviceId":"phone-1","deviceName":"Pixel"}""",
            ),
            Json.parseToJsonElement(request.body.readUtf8()),
        )
    }

    @Test
    fun exchangeWritesRedactedRequestAndResponseLogs() {
        server.enqueue(jsonResponse("""{"protocolVersion":1,"deviceId":"phone-1","credential":"credential-1"}"""))
        val logs = DiagnosticLogStore()
        val loggedClient = BridgeHttpClient(logs = logs)
        val invitation = PairingInvitation.parse(
            "codex-remote://pair?baseUrl=${encodedBaseUrl()}&token=pair-token",
        )

        loggedClient.exchange(invitation, "phone-1", "Pixel")

        val rendered = logs.export()
        assertTrue(rendered.contains("request POST /v1/pair/exchange"))
        assertTrue(rendered.contains("HTTP 200"))
        assertFalse(rendered.contains("pair-token"))
        assertFalse(rendered.contains("credential-1"))
    }

    @Test
    fun snapshotSendsAuthorizationAndDecodesModelsAndThreads() {
        server.enqueue(
            jsonResponse(
                """
                {
                  "protocolVersion": 1,
                  "eventCursor": 42,
                  "capabilities": {
                    "readThreads": true,
                    "startTask": true,
                    "sendTurn": true,
                    "steer": true,
                    "stopTurn": true
                  },
                  "projects": [{"id":"project-1","displayName":"Example"}],
                  "models": [{
                    "id":"gpt-test",
                    "displayName":"Test Model",
                    "reasoningOptions":[{"id":"high","displayName":"High"}]
                  }],
                  "threads": [{
                    "id":"thread-1",
                    "title":"Build",
                    "projectId":"project-1",
                    "projectName":"Example",
                    "source":"desktop",
                    "state":"running",
                    "updatedAt":"2026-08-25T12:00:00Z"
                  }],
                  "futureField": "ignored"
                }
                """.trimIndent(),
            ),
        )
        val credential = DeviceCredential(1, "phone-1", "credential-1")

        val snapshot = client.snapshot(baseUrl(), credential)

        assertEquals(42L, snapshot.eventCursor)
        assertTrue(snapshot.capabilities.readThreads)
        assertEquals("gpt-test", snapshot.models.single().id)
        assertEquals("high", snapshot.models.single().reasoningOptions.single().id)
        assertEquals("thread-1", snapshot.threads.single().id)
        val request = server.takeRequest()
        assertEquals("/v1/snapshot", request.path)
        assertEquals("Device phone-1:credential-1", request.getHeader("Authorization"))
    }

    @Test
    fun sendCommandPostsAuthenticatedEnvelopeAndDecodesResponse() {
        server.enqueue(jsonResponse("""{"status":"completed","result":{"threadId":"thread-1"}}"""))
        val credential = DeviceCredential(1, "phone-1", "credential-1")
        val command = CommandEnvelope(
            protocolVersion = 1,
            requestId = "request-1",
            deviceId = "phone-1",
            idempotencyKey = "start-1",
            type = "task.start",
            payload = buildJsonObject { put("prompt", "Build") },
            sentAt = "2026-08-25T12:00:00Z",
        )

        val response = client.sendCommand(baseUrl(), credential, command)

        assertEquals("completed", response.status)
        assertEquals("thread-1", response.result?.let { it.jsonObject["threadId"]?.jsonPrimitive?.content })
        val request = server.takeRequest()
        assertEquals("/v1/commands", request.path)
        assertEquals("Device phone-1:credential-1", request.getHeader("Authorization"))
        assertEquals(Json.encodeToJsonElement(CommandEnvelope.serializer(), command), Json.parseToJsonElement(request.body.readUtf8()))
    }

    @Test
    fun threadTurnsKeepsPageSizeWhenLoadingWithCursor() {
        server.enqueue(jsonResponse("""{"turns":[],"nextCursor":null}"""))
        val credential = DeviceCredential(1, "phone-1", "credential-1")

        client.threadTurns(baseUrl(), credential, "thread-1", cursor = "cursor-2", limit = 50)

        assertEquals("/v1/threads/thread-1/turns?limit=50&cursor=cursor-2", server.takeRequest().path)
    }

    @Test
    fun unauthorizedExceptionDoesNotExposeSecretsOrRawResponse() {
        val credentialSecret = "credential-do-not-leak"
        val responseSecret = "response-do-not-leak"
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"rejected $responseSecret"}"""),
        )

        try {
            client.snapshot(baseUrl(), DeviceCredential(1, "phone-1", credentialSecret))
            fail("unauthorized request must throw")
        } catch (error: BridgeApiException) {
            assertEquals(401, error.statusCode)
            val exposed = error.toString() + error.message.orEmpty() + error.safeMessage
            assertFalse(exposed.contains(credentialSecret))
            assertFalse(exposed.contains(responseSecret))
        }
    }

    @Test
    fun activeWriterConflictPreservesSafeServerDetail() {
        val logs = DiagnosticLogStore()
        val loggedClient = BridgeHttpClient(logs = logs)
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"command rejected","detail":"thread abc already has an active writer","code":-32600}"""),
        )

        try {
            loggedClient.sendCommand(
                baseUrl(),
                DeviceCredential(1, "phone-1", "credential-1"),
                CommandEnvelope(1, "request-1", "phone-1", "idempotency-1", "thread.send", buildJsonObject { put("prompt", "继续") }, "2026-08-25T12:00:00Z"),
            )
            fail("active writer response must throw")
        } catch (error: BridgeApiException) {
            assertEquals(409, error.statusCode)
            assertTrue(error.safeMessage.contains("active writer"))
            assertTrue(logs.export().contains("active writer"))
        }
    }

    @Test
    fun pairingFailureDoesNotExposeTokenOrRawResponse() {
        val tokenSecret = "token-do-not-leak"
        val responseSecret = "response-do-not-leak"
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"rejected $responseSecret"}"""),
        )
        val invitation = PairingInvitation.parse(
            "codex-remote://pair?baseUrl=${encodedBaseUrl()}&token=$tokenSecret",
        )

        try {
            client.exchange(invitation, "phone-1", "Pixel")
            fail("rejected pairing request must throw")
        } catch (error: BridgeApiException) {
            val exposed = error.toString() + error.message.orEmpty() + error.safeMessage
            assertFalse(exposed.contains(tokenSecret))
            assertFalse(exposed.contains(responseSecret))
        }
    }

    @Test
    fun pairingExchangeDoesNotFollowRedirects() {
        server.enqueue(
            MockResponse()
                .setResponseCode(307)
                .setHeader("Location", server.url("/redirected")),
        )
        server.enqueue(
            jsonResponse(
                """{"protocolVersion":1,"deviceId":"phone-1","credential":"credential-1"}""",
            ),
        )
        val invitation = PairingInvitation.parse(
            "codex-remote://pair?baseUrl=${encodedBaseUrl()}&token=pair-token",
        )

        try {
            client.exchange(invitation, "phone-1", "Pixel")
            fail("redirect response must throw")
        } catch (error: BridgeApiException) {
            assertEquals(307, error.statusCode)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun pairingExchangeDoesNotRetryServiceUnavailableResponse() {
        val tokenSecret = "token-do-not-leak"
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Retry-After", "0"),
        )
        server.enqueue(
            jsonResponse(
                """{"protocolVersion":1,"deviceId":"phone-1","credential":"credential-1"}""",
            ),
        )
        val invitation = PairingInvitation.parse(
            "codex-remote://pair?baseUrl=${encodedBaseUrl()}&token=$tokenSecret",
        )

        try {
            client.exchange(invitation, "phone-1", "Pixel")
            fail("service unavailable response must throw")
        } catch (error: BridgeApiException) {
            assertEquals(503, error.statusCode)
            assertEquals(1, server.requestCount)
            assertFalse(error.toString().contains(tokenSecret))
        }
    }

    @Test
    fun malformedSuccessResponseDoesNotExposeRawJson() {
        val responseSecret = "credential-do-not-leak"
        server.enqueue(
            jsonResponse(
                """{"protocolVersion":1,"deviceId":"phone-1","credential":"$responseSecret"""",
            ),
        )
        val invitation = PairingInvitation.parse(
            "codex-remote://pair?baseUrl=${encodedBaseUrl()}&token=pair-token",
        )

        try {
            client.exchange(invitation, "phone-1", "Pixel")
            fail("malformed response must throw")
        } catch (error: BridgeApiException) {
            val exposed = error.toString() + error.message.orEmpty() + error.safeMessage
            assertFalse(exposed.contains(responseSecret))
        }
    }

    @Test
    fun credentialToStringDoesNotExposeSecret() {
        val secret = "credential-do-not-leak"

        val rendered = DeviceCredential(1, "phone-1", secret).toString()

        assertFalse(rendered.contains(secret))
    }

    @Test
    fun pairingRequestToStringDoesNotExposeToken() {
        val secret = "token-do-not-leak"

        val rendered = PairExchangeRequest(secret, "phone-1", "Pixel").toString()

        assertFalse(rendered.contains(secret))
    }

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    private fun encodedBaseUrl(): String =
        java.net.URLEncoder.encode(baseUrl(), Charsets.UTF_8.name())

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
