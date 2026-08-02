package dev.zipshare.data.net

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dev.zipshare.BuildConfig
import dev.zipshare.data.model.Profile
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One OkHttp client per profile: pinning, cleartext policy and the auth header are all per-profile.
 * There is no global cleartext switch and no shared authenticated client.
 */
@Singleton
class ZiplineClients @Inject constructor() {

    /**
     * `encodeDefaults = true` matters: without it the serializer omits any property equal to its
     * declared default, so a body like `DeleteFolderBody(delete = "folder")` went out with the
     * field missing and the server rejected it (E1000). That bit three separate request bodies
     * before being fixed here rather than per-DTO.
     *
     * `explicitNulls = false` stays, because for PATCH bodies an absent field means "unchanged" -
     * only a deliberately built JSON null (see the file-password call) means "clear this".
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }
    private val pool = ConnectionPool(4, 5, TimeUnit.MINUTES)
    private val apis = ConcurrentHashMap<String, ZiplineApi>()
    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    /** Credential-free client, used for TOFU pin fetching. */
    fun bare(allowCleartext: Boolean): OkHttpClient = base(allowCleartext).build()

    fun api(profile: Profile): ZiplineApi = apis.getOrPut(key(profile)) {
        Retrofit.Builder()
            .baseUrl(profile.retrofitBase)
            .client(client(profile))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ZiplineApi::class.java)
    }

    fun client(profile: Profile): OkHttpClient = clients.getOrPut(key(profile)) { buildClient(profile) }

    fun invalidate(profileId: String) {
        val prefix = "$profileId|"
        apis.keys.filter { it.startsWith(prefix) }.forEach(apis::remove)
        clients.keys.filter { it.startsWith(prefix) }.forEach(clients::remove)
    }

    /** Token contributes only as a hash, so the plaintext never lands in a map key. */
    private fun key(p: Profile) =
        "${p.id}|${p.baseUrl}|${p.token.hashCode()}|${p.pinnedSpkiSha256}|${p.allowCleartext}"

    private fun base(allowCleartext: Boolean) = OkHttpClient.Builder()
        .connectionPool(pool)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // No write or call timeout: a slow multi-GiB upload is not a stuck socket.
        .writeTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // RESTRICTED_TLS and MODERN_TLS are TLS 1.2/1.3 only. COMPATIBLE_TLS is deliberately
        // excluded: it widens the cipher list to weaker suites, which is exactly what a
        // downgrade-capable attacker wants. A server too old for TLS 1.2 should be fixed, not
        // accommodated.
        .connectionSpecs(
            if (allowCleartext) {
                listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)
            } else {
                // Omitting CLEARTEXT makes OkHttp refuse http:// even where the platform config allows it.
                listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS)
            },
        )

    /**
     * Unauthenticated client with a cookie jar, for the username/password flow: `/api/auth/login`
     * opens a *session* rather than returning a token, so its cookie has to survive into the
     * `/api/user/token` call that exchanges it. Never cached - every attempt gets a fresh jar, so
     * a failed login cannot leave a stale session behind. Pinning still applies.
     */
    fun loginApi(profile: Profile): ZiplineApi {
        val jar = object : CookieJar {
            private val store = mutableListOf<Cookie>()

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(store) { store.addAll(cookies) }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                synchronized(store) { store.filter { it.matches(url) } }
        }
        val client = base(profile.allowCleartext).cookieJar(jar).apply { applyPinning(this, profile) }.build()
        return Retrofit.Builder()
            .baseUrl(profile.retrofitBase)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ZiplineApi::class.java)
    }

    /**
     * Several pins may be given (comma / whitespace separated). Pinning a backup alongside the
     * active one is what stops a certificate change from locking the app out of the server.
     */
    private fun applyPinning(b: OkHttpClient.Builder, profile: Profile) {
        val pins = profile.pinnedSpkiSha256
            ?.split(',', ' ', '\n', '\t')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val host = profile.host
        if (pins.isNotEmpty() && !host.isNullOrEmpty()) {
            val builder = CertificatePinner.Builder()
            pins.forEach { pin ->
                builder.add(host, if (pin.startsWith("sha256/")) pin else "sha256/$pin")
            }
            b.certificatePinner(builder.build())
        }
    }

    private fun buildClient(profile: Profile): OkHttpClient {
        val b = base(profile.allowCleartext)

        b.addInterceptor { chain ->
            // Raw token, no "Bearer " prefix, on every request. Never placed in a URL.
            chain.proceed(chain.request().newBuilder().header("authorization", profile.token).build())
        }

        applyPinning(b, profile)

        if (BuildConfig.DEBUG) {
            b.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                    redactHeader("authorization")
                    redactHeader("cookie")
                    redactHeader("set-cookie")
                    redactHeader("x-zipline-password")
                },
            )
        }
        return b.build()
    }
}
