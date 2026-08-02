package dev.zipshare.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.Request
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton

enum class CertRole { LEAF, INTERMEDIATE, ROOT }

data class ChainPin(
    val subject: String,
    val issuer: String,
    val pin: String,
    val notAfter: String,
    val role: CertRole,
    /**
     * The intermediate is the sane thing to pin. Leaf certs from an ACME/Cloudflare-style issuer
     * rotate every few months, and a leaf pin silently breaks the app on every renewal; the
     * issuing CA key is stable for years.
     */
    val recommended: Boolean = false,
)

/**
 * Fetches the live certificate chain so the user can confirm a pin (TOFU) instead of pasting blind.
 * Runs on an unpinned client — pinning the fetch would defeat the purpose.
 */
@Singleton
class PinFetcher @Inject constructor(private val clients: ZiplineClients) {

    suspend fun fetch(baseUrl: HttpUrl): List<ChainPin> = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder().addPathSegments("api/healthcheck").build()
        clients.bare(allowCleartext = false)
            .newCall(Request.Builder().url(url).build())
            .execute()
            .use { response ->
                val handshake = response.handshake ?: throw ZiplineException(
                    code = 0,
                    statusCode = 0,
                    serverError = "No TLS handshake",
                    display = "That URL is not served over TLS, so there is nothing to pin.",
                    action = ErrorAction.NONE,
                )
                val chain = handshake.peerCertificates.filterIsInstance<X509Certificate>()
                val pins = chain.mapIndexed { index, cert ->
                    val selfSigned = cert.subjectX500Principal == cert.issuerX500Principal
                    ChainPin(
                        subject = cert.subjectX500Principal.name,
                        issuer = cert.issuerX500Principal.name,
                        pin = CertificatePinner.pin(cert),
                        notAfter = cert.notAfter.toString(),
                        role = when {
                            index == 0 -> CertRole.LEAF
                            selfSigned -> CertRole.ROOT
                            else -> CertRole.INTERMEDIATE
                        },
                    )
                }
                // Prefer the first intermediate; fall back to the leaf on a chain without one.
                val target = pins.firstOrNull { it.role == CertRole.INTERMEDIATE } ?: pins.firstOrNull()
                pins.map { it.copy(recommended = it.pin == target?.pin) }
            }
    }
}
