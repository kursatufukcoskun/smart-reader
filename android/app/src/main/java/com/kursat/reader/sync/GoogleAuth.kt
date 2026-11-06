package com.kursat.reader.sync

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.kursat.reader.ai.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object GoogleAuth {
    const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    const val EMAIL_SCOPE = "email"

    private const val KEY_ACCESS_TOKEN = KeyStore.GOOGLE_ACCESS_TOKEN
    private const val KEY_REFRESH_TOKEN = KeyStore.GOOGLE_REFRESH_TOKEN
    private const val KEY_USER_EMAIL = "google.user_email"
    private const val KEY_CLIENT_ID = "google.android_client_id"

    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    private val _userEmail = MutableStateFlow<String?>(null)
    val userEmail: StateFlow<String?> = _userEmail.asStateFlow()

    private var accessToken: String? = null
    private var tokenExpiry: Date = Date(0)
    private var appContext: Context? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun init(context: Context) {
        appContext = context.applicationContext
        val savedToken = KeyStore.get(KEY_ACCESS_TOKEN)
        val savedRefresh = KeyStore.get(KEY_REFRESH_TOKEN)
        _userEmail.value = KeyStore.get(KEY_USER_EMAIL)
        if (!savedToken.isNullOrBlank()) {
            accessToken = savedToken
            _isSignedIn.value = true
        } else if (!savedRefresh.isNullOrBlank()) {
            _isSignedIn.value = true
        }
    }

    var customClientId: String
        get() = KeyStore.get(KEY_CLIENT_ID).orEmpty()
        set(value) { KeyStore.set(value.trim(), KEY_CLIENT_ID) }

    suspend fun authorizeWithPlayServices(context: Context): Intent? = suspendCancellableCoroutine { cont ->
        val req = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE), Scope(EMAIL_SCOPE)))
            .build()

        Identity.getAuthorizationClient(context)
            .authorize(req)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    cont.resume(
                        Intent().putExtra("pendingIntent", result.pendingIntent)
                    )
                } else {
                    val token = result.accessToken
                    if (!token.isNullOrBlank()) {
                        setAccessToken(token, expiresInSeconds = 3500)
                        cont.resume(null)
                    } else {
                        cont.resumeWithException(Exception("Google erişim anahtarı alınamadı."))
                    }
                }
            }
            .addOnFailureListener { e ->
                cont.resumeWithException(e)
            }
    }

    fun handleAuthorizationResult(context: Context, data: Intent?): Boolean {
        return try {
            val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
            val token = result.accessToken
            if (!token.isNullOrBlank()) {
                setAccessToken(token, expiresInSeconds = 3500)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    fun setAccessToken(token: String, expiresInSeconds: Int = 3600, email: String? = null) {
        accessToken = token.trim()
        tokenExpiry = Date(System.currentTimeMillis() + (expiresInSeconds - 60) * 1000L)
        KeyStore.set(token.trim(), KEY_ACCESS_TOKEN)
        if (!email.isNullOrBlank()) {
            _userEmail.value = email
            KeyStore.set(email, KEY_USER_EMAIL)
        }
        _isSignedIn.value = true
    }

    fun signOut() {
        accessToken = null
        tokenExpiry = Date(0)
        KeyStore.set("", KEY_ACCESS_TOKEN)
        KeyStore.set("", KEY_REFRESH_TOKEN)
        KeyStore.set("", KEY_USER_EMAIL)
        _userEmail.value = null
        _isSignedIn.value = false
    }

    suspend fun validAccessToken(): String = withContext(Dispatchers.IO) {
        val current = accessToken
        if (!current.isNullOrBlank() && Date().before(tokenExpiry)) {
            return@withContext current
        }
        val ctx = appContext
        if (ctx != null) {
            try {
                val req = AuthorizationRequest.builder()
                    .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE), Scope(EMAIL_SCOPE)))
                    .build()
                val token = suspendCancellableCoroutine<String?> { cont ->
                    Identity.getAuthorizationClient(ctx)
                        .authorize(req)
                        .addOnSuccessListener { res ->
                            if (!res.hasResolution() && !res.accessToken.isNullOrBlank()) {
                                cont.resume(res.accessToken)
                            } else {
                                cont.resume(null)
                            }
                        }
                        .addOnFailureListener { cont.resume(null) }
                }
                if (!token.isNullOrBlank()) {
                    setAccessToken(token, 3500)
                    if (_userEmail.value == null) fetchUserEmail(token)
                    return@withContext token
                }
            } catch (_: Exception) {}
        }

        val saved = KeyStore.get(KEY_ACCESS_TOKEN)
        if (!saved.isNullOrBlank()) {
            accessToken = saved
            return@withContext saved
        }
        throw Exception("Google oturumu açık değil veya erişim süresi doldu.")
    }

    suspend fun fetchUserEmail(token: String) = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://www.googleapis.com/oauth2/v2/userinfo")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val info = json.decodeFromString<UserInfo>(body)
                    if (!info.email.isNullOrBlank()) {
                        _userEmail.value = info.email
                        KeyStore.set(info.email, KEY_USER_EMAIL)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    @Serializable
    private data class UserInfo(val email: String? = null)
}
