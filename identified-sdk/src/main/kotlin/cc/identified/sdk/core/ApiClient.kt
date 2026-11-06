package cc.identified.sdk.core

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal enum class SessionFailure {
    INVALID,
    EXPIRED,
}

internal class ApiException(
    val status: Int,
    override val message: String,
    val exhausted: Boolean = false,
) : Exception(message) {
    val sessionFailure: SessionFailure?
        get() = sessionFailure(status, message)

    companion object {
        fun sessionFailure(status: Int, message: String): SessionFailure? =
            if (status != 401) null
            else if (message.contains("expired", ignoreCase = true)) SessionFailure.EXPIRED
            else SessionFailure.INVALID
    }
}

/** Session-only client. Responses, tokens and captured bytes are never persisted. */
internal class ApiClient(
    val baseUrl: String,
    private val sessionToken: String,
    httpClient: OkHttpClient = OkHttpClient(),
) {
    private val base by lazy {
        try {
            baseUrl.toHttpUrl()
        } catch (error: IllegalArgumentException) {
            throw ApiException(0, "Invalid base URL")
        }
    }
    private val client =
        httpClient
            .newBuilder()
            .cache(null)
            .cookieJar(CookieJar.NO_COOKIES)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(requestTimeout, TimeUnit.SECONDS)
            .connectTimeout(requestTimeout, TimeUnit.SECONDS)
            .readTimeout(requestTimeout, TimeUnit.SECONDS)
            .writeTimeout(requestTimeout, TimeUnit.SECONDS)
            .build()
    private val uploadClient =
        client
            .newBuilder()
            .callTimeout(uploadTimeout, TimeUnit.SECONDS)
            .readTimeout(uploadTimeout, TimeUnit.SECONDS)
            .writeTimeout(uploadTimeout, TimeUnit.SECONDS)
            .build()

    suspend fun currentSession(): HostedSession =
        decode(exchange(request("GET", "api/v1/sessions/current")))

    suspend fun saveMetadata(patch: JsonObject) {
        ignored(exchange(request("PATCH", "api/v1/sessions/metadata", patch)))
    }

    suspend fun recordConsent(scopes: List<String>) {
        ignored(
            exchange(
                request(
                    "POST",
                    "api/v1/sessions/consent",
                    buildJsonObject {
                        put("version", ConsentVersion.current)
                        put("scopes", JsonArray(scopes.map(::JsonPrimitive)))
                    },
                )
            )
        )
    }

    suspend fun getOrCreateCase(session: HostedSession): VerificationCase =
        decode(
            exchange(
                request(
                    "POST",
                    "api/verification/cases",
                    buildJsonObject {
                        put("userId", session.id)
                        put(
                            "subjectId",
                            session.subjectId?.takeIf { it.isNotEmpty() } ?: session.subjectRef,
                        )
                        put(
                            "requiredChecks",
                            JsonArray(session.requiredChecks.map(::JsonPrimitive)),
                        )
                        put(
                            "requiredDocuments",
                            JsonArray(session.requiredDocuments.map(::JsonPrimitive)),
                        )
                    },
                )
            )
        )

    suspend fun getCase(id: String): VerificationCase =
        decode(exchange(request("GET", "api/verification/cases", segments = listOf(id))))

    suspend fun uploadDocument(
        caseId: String,
        docType: String,
        captureRole: String?,
        data: ByteArray,
        mimeType: String,
    ) {
        val body =
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("type", docType)
                .apply {
                    if (captureRole != null) addFormDataPart("capture_role", captureRole)
                    addFormDataPart(
                        "file",
                        uploadFilename(docType, mimeType),
                        data.toRequestBody(mimeType.toMediaType()),
                    )
                }
                .build()
        exchange(
            request(
                "POST",
                "api/verification/cases",
                segments = listOf(caseId, "documents"),
                body = body,
            ),
            upload = true,
        )
    }

    suspend fun runBiometricCheck(caseId: String): VerificationCheck =
        decode(
            exchange(
                request(
                    "POST",
                    "api/verification/cases",
                    buildJsonObject { put("checkType", CheckType.biometricFaceMatch) },
                    segments = listOf(caseId, "checks", "biometric"),
                )
            )
        )

    suspend fun submitCase(id: String): SubmitResult =
        decode(exchange(request("POST", "api/verification/cases", segments = listOf(id, "submit"))))

    suspend fun resolveDigitalAddress(code: String): DigitalAddress =
        decode(
            exchange(
                request("GET", "api/v1/addresses/ghanapost/resolve", query = mapOf("code" to code))
            )
        )

    suspend fun encodeDigitalAddress(latitude: Double, longitude: Double): DigitalAddress =
        decode(
            exchange(
                request(
                    "GET",
                    "api/v1/addresses/ghanapost/encode",
                    query = mapOf("lat" to latitude.toString(), "lng" to longitude.toString()),
                )
            )
        )

    private class CaseEndpoint

    private fun request(
        method: String,
        path: String,
        json: JsonObject? = null,
        segments: List<String> = emptyList(),
        query: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
    ): Request {
        val url =
            base
                .newBuilder()
                .apply {
                    // Preserve a configured base path, treating it as a directory.
                    if (base.encodedPath.endsWith("/")) removePathSegment(base.pathSize - 1)
                    addPathSegments(path)
                    segments.forEach { addPathSegment(it) }
                    query.forEach { (key, value) -> addQueryParameter(key, value) }
                }
                .build()
        val requestBody =
            body
                ?: json?.toString()?.toRequestBody("application/json".toMediaType())
                ?: if (method in setOf("POST", "PATCH", "PUT")) ByteArray(0).toRequestBody()
                else null
        return Request.Builder()
            .url(url)
            .apply {
                if (path == "api/verification/cases") tag(CaseEndpoint::class.java, CaseEndpoint())
            }
            .header("Authorization", "Bearer $sessionToken")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .method(method, requestBody)
            .build()
    }

    /**
     * The combined case middleware masks hosted-token expiration as a management failure. Probe the
     * hosted-only endpoint once, without retrying or resending case mutations.
     */
    private suspend fun exchange(request: Request, upload: Boolean = false): String {
        try {
            return rawExchange(request, upload)
        } catch (original: ApiException) {
            if (
                request.tag(CaseEndpoint::class.java) == null ||
                    original.status != 401 ||
                    original.message != "invalid management session"
            )
                throw original
            try {
                rawExchange(request("GET", "api/v1/sessions/current"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (probe: ApiException) {
                if (probe.status == 401) throw probe
            } catch (_: Exception) {
                // The diagnostic lookup must not replace the original case failure.
            }
            throw original
        }
    }

    private suspend fun rawExchange(request: Request, upload: Boolean = false): String =
        suspendCancellableCoroutine { continuation ->
            val call = (if (upload) uploadClient else client).newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive)
                            continuation.resumeWithException(
                                ApiException(0, e.message ?: "Network request failed")
                            )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            try {
                                val text = it.body?.string().orEmpty()
                                if (!it.isSuccessful) {
                                    val parsed =
                                        runCatching {
                                                CoreJson.parseToJsonElement(text) as? JsonObject
                                            }
                                            .getOrNull()
                                    val message =
                                        (parsed?.get("error") as? JsonPrimitive)
                                            ?.takeIf { p -> p.isString }
                                            ?.content
                                            ?: if (upload && it.code == 422)
                                                "We couldn't accept this document. Try a clearer photo, or a different document from the list."
                                            else "Request failed (${it.code})"
                                    val exhausted =
                                        (parsed?.get("exhausted") as? JsonPrimitive)?.booleanOrNull
                                            ?: false
                                    if (continuation.isActive)
                                        continuation.resumeWithException(
                                            ApiException(
                                                it.code,
                                                message,
                                                upload && it.code == 422 && exhausted,
                                            )
                                        )
                                } else if (continuation.isActive) continuation.resume(text)
                            } catch (e: Exception) {
                                if (continuation.isActive)
                                    continuation.resumeWithException(
                                        if (e is IOException)
                                            ApiException(0, e.message ?: "Network request failed")
                                        else e
                                    )
                            }
                        }
                    }
                }
            )
        }

    private fun envelope(text: String): JsonElement =
        try {
            (CoreJson.parseToJsonElement(text) as? JsonObject)?.get("data")?.takeUnless {
                it is JsonNull
            } ?: throw IllegalArgumentException("Missing data envelope")
        } catch (e: Exception) {
            throw ApiException(0, "Invalid response: ${e.message}")
        }

    private inline fun <reified T> decode(text: String): T =
        try {
            CoreJson.decodeFromJsonElement<T>(envelope(text))
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(0, "Invalid response: ${e.message}")
        }

    private fun ignored(text: String) {
        if (envelope(text) !is JsonObject)
            throw ApiException(0, "Invalid response: expected data object")
    }

    companion object {
        const val requestTimeout = 30L
        const val uploadTimeout = 120L

        fun uploadFilename(docType: String, mimeType: String): String {
            val extension =
                when (mimeType) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "application/pdf" -> "pdf"
                    else -> "jpg"
                }
            return "$docType.$extension"
        }
    }
}
