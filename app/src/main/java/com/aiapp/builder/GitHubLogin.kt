package com.aiapp.builder

import com.aiapp.llm.Http
import com.aiapp.llm.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.Request

/**
 * "Sign in with GitHub" using GitHub's device flow: the app shows a short code, you approve it on
 * github.com once, and the app gets a token to create repos and run builds. No secret is needed.
 */
object GitHubLogin {
    /**
     * Client ID of the AI App OAuth app (public, not a secret). Created at
     * github.com/settings/developers with "Enable Device Flow" ticked. Can be overridden in Settings.
     */
    const val DEFAULT_CLIENT_ID = ""

    const val SCOPES = "public_repo workflow"

    data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val interval: Int, val expiresIn: Int)

    private suspend fun postForm(url: String, form: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(url).header("Accept", "application/json").post(body).build()
        Http.client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ProviderException(r.code, "GitHub sign-in failed (HTTP ${r.code}).")
            Http.json.parseToJsonElement(text).jsonObject
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    suspend fun start(clientId: String): DeviceCode {
        val r = postForm("https://github.com/login/device/code", mapOf("client_id" to clientId, "scope" to SCOPES))
        r.str("error")?.let {
            throw ProviderException(400, if (it == "device_flow_disabled") "Turn on \"Enable Device Flow\" in your GitHub OAuth app." else "GitHub: ${r.str("error_description") ?: it}")
        }
        return DeviceCode(
            deviceCode = r.str("device_code") ?: throw ProviderException(500, "GitHub didn't return a code."),
            userCode = r.str("user_code").orEmpty(),
            verificationUri = r.str("verification_uri") ?: "https://github.com/login/device",
            interval = (r["interval"] as? JsonPrimitive)?.intOrNull ?: 5,
            expiresIn = (r["expires_in"] as? JsonPrimitive)?.intOrNull ?: 900,
        )
    }

    /** Waits until you approve the code on github.com, then returns the access token. */
    suspend fun waitForToken(clientId: String, code: DeviceCode): String {
        var interval = code.interval.coerceAtLeast(5)
        val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val r = postForm(
                "https://github.com/login/oauth/access_token",
                mapOf(
                    "client_id" to clientId,
                    "device_code" to code.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                ),
            )
            r.str("access_token")?.let { return it }
            when (r.str("error")) {
                "authorization_pending", null -> Unit
                "slow_down" -> interval += 5
                "expired_token" -> throw ProviderException(408, "The code expired. Tap Sign in again.")
                "access_denied" -> throw ProviderException(403, "Sign-in was cancelled on GitHub.")
                else -> throw ProviderException(400, "GitHub: ${r.str("error_description") ?: r.str("error")}")
            }
        }
        throw ProviderException(408, "The code expired. Tap Sign in again.")
    }
}
