package dev.zipshare.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class UploadResponse(
    val files: List<UploadedFile> = emptyList(),
    val deletesAt: String? = null,
    val assumedMimetypes: List<Boolean>? = null,
    // partial-upload only
    val partialSuccess: Boolean? = null,
    val partialIdentifier: String? = null,
)

@Serializable
data class UploadedFile(
    val id: String,
    val name: String,
    val type: String,
    val url: String,
    val pending: Boolean? = null,
    val removedGps: Boolean? = null,
)

@Serializable
data class ApiErrorBody(
    val error: String = "",
    val code: Int = 0,
    val statusCode: Int = 0,
)

@Serializable
data class HealthcheckResponse(val pass: Boolean = false)

@Serializable
data class VersionResponse(
    /** Absent when the server could not reach GitHub to compare releases. */
    val data: VersionData? = null,
    val details: VersionDetails? = null,
    val cached: Boolean = false,
)

/** The comparison the web dashboard's Version panel renders. */
@Serializable
data class VersionData(
    val isUpstream: Boolean = false,
    val isRelease: Boolean = false,
    /** True when the running build is the newest release. */
    val isLatest: Boolean = true,
    val version: VersionTag? = null,
    val latest: VersionTag? = null,
)

@Serializable
data class VersionTag(
    val tag: String? = null,
    val sha: String? = null,
    val url: String? = null,
)

@Serializable
data class VersionDetails(
    val version: String = "unknown",
    val sha: String? = null,
)

@Serializable
data class UserResponse(val user: ZUser? = null)

@Serializable
data class ZUser(
    val id: String,
    val username: String,
    val role: String = "USER",
    /** Base64 data URL (`data:image/png;base64,...`) or null when no avatar is set. */
    val avatar: String? = null,
    val quota: ZQuota? = null,
    /** Per-user view-route settings; absent on older servers. */
    val view: ZView? = null,
)

/**
 * Zipline's "Viewing Files" settings - how a file looks on its `/view/<id>` page and what
 * OpenGraph tags it emits. Every field is nullable because the server sends only what is set,
 * and because a PATCH must be able to send one field without clearing the rest.
 */
@Serializable
data class ZView(
    val enabled: Boolean? = null,
    val disableTextFiles: Boolean? = null,
    val showMimetype: Boolean? = null,
    val showTags: Boolean? = null,
    val showFolder: Boolean? = null,
    val content: String? = null,
    /** left | center | right */
    val align: String? = null,
    val embed: Boolean? = null,
    val embedMediaOnly: Boolean? = null,
    val embedTitle: String? = null,
    val embedDescription: String? = null,
    val embedSiteName: String? = null,
    val embedColor: String? = null,
)

@Serializable
data class ZQuota(
    val filesQuota: String? = null,
    val maxBytes: String? = null,
    val maxFiles: Int? = null,
    val maxUrls: Int? = null,
)

@Serializable
data class ZFolder(
    val id: String,
    val name: String,
    @SerialName("public") val isPublic: Boolean = false,
    val allowUploads: Boolean = false,
    val parentId: String? = null,
)

@Serializable
data class FilesPage(
    val page: List<ZFile> = emptyList(),
    val total: Int? = null,
    val pages: Int? = null,
)

@Serializable
data class ZFile(
    val id: String,
    val name: String,
    val type: String = "application/octet-stream",
    val size: Long = 0,
    /** Route-relative on this endpoint; resolve against the profile base url before display. */
    val url: String? = null,
    val createdAt: String? = null,
    val deletesAt: String? = null,
    val views: Int = 0,
    val maxViews: Int? = null,
    val favorite: Boolean = false,
    val originalName: String? = null,
    val folderId: String? = null,
    val updatedAt: String? = null,
    val tags: List<ZTag> = emptyList(),
    /**
     * The server types this as `string | boolean | null`: list responses report whether a
     * password exists, while a single-file fetch may carry the hash. Kept as a raw element so
     * either shape parses, and read through [hasPassword].
     */
    val password: JsonElement? = null,
    val thumbnail: ZThumbnail? = null,
)

/** True when the file is password protected, whichever shape the server used. */
fun ZFile.hasPassword(): Boolean = when (val p = password) {
    null, JsonNull -> false
    is JsonPrimitive -> if (p.isString) p.content.isNotBlank() else p.content != "false"
    else -> false
}

@Serializable
data class ZThumbnail(val path: String)

/** GET /api/user/tags. `color` is a hex string the server validates as #rgb or #rrggbb. */
@Serializable
data class ZTag(
    val id: String,
    val name: String,
    val color: String = "#888888",
)

@Serializable
data class CreateTagBody(val name: String, val color: String)

/** PATCH /api/user/tags/{id}. Both optional; E1034 if the name collides with another tag. */
@Serializable
data class PatchTagBody(val name: String? = null, val color: String? = null)

/** The sort options Zipline accepts on /api/user/files, with labels for the menu. */
enum class FileSort(val wire: String, val label: String) {
    CREATED("createdAt", "Date uploaded"),
    NAME("name", "Name"),
    SIZE("size", "Size"),
    TYPE("type", "File type"),
    VIEWS("views", "Views"),
}

/**
 * PATCH /api/user/files/{id}. Every field is optional - only what changed is sent, because the
 * server applies whatever it receives. `tags` is a list of tag *ids*, not names.
 */
@Serializable
data class PatchFileBody(
    val favorite: Boolean? = null,
    val maxViews: Int? = null,
    val name: String? = null,
    val originalName: String? = null,
    val type: String? = null,
    val tags: List<String>? = null,
    val password: String? = null,
)

/** PATCH /api/user/files/transaction - bulk favourite or move. */
@Serializable
data class BulkPatchBody(
    val files: List<String>,
    val favorite: Boolean? = null,
    val folder: String? = null,
)

/**
 * DELETE /api/user/files/transaction - bulk delete.
 *
 * [deleteDatasourceFiles] deliberately has NO default: the serializer omits properties equal to
 * their default, so a default here meant the flag never reached the server and the bytes were
 * left orphaned in storage while the database row vanished.
 */
@Serializable
data class BulkDeleteBody(
    val files: List<String>,
    @SerialName("delete_datasourceFiles") val deleteDatasourceFiles: Boolean,
)

/** GET /api/user/urls */
@Serializable
data class ZUrl(
    val id: String,
    val code: String,
    val vanity: String? = null,
    val destination: String,
    val views: Int = 0,
    val maxViews: Int? = null,
    val enabled: Boolean = true,
    val createdAt: String? = null,
)

/** Zipline serves short links from /go/<code>; the api returns the code, never the full link. */
fun ZUrl.shortLink(baseUrl: String): String = "${baseUrl.trimEnd('/')}/go/${vanity ?: code}"

/** GET /api/users - admin only, returns the limited user shape. */
@Serializable
data class ZLimitedUser(
    val id: String,
    val username: String,
    val role: String = "USER",
    val createdAt: String? = null,
    val quota: ZQuota? = null,
)

// ---------- instance metrics: GET /api/stats ----------

@Serializable
data class ApiStats(
    val latest: ZMetric? = null,
    val points: List<ZMetricPoint> = emptyList(),
)

@Serializable
data class ZMetric(
    val id: String = "",
    val createdAt: String? = null,
    val data: ZMetricData = ZMetricData(),
)

@Serializable
data class ZMetricData(
    val users: Int = 0,
    val files: Int = 0,
    val fileViews: Int = 0,
    val urls: Int = 0,
    val urlViews: Int = 0,
    val storage: Double = 0.0,
    val filesUsers: List<ZUserSum> = emptyList(),
    val urlsUsers: List<ZUrlUserSum> = emptyList(),
    val types: List<ZTypeSum> = emptyList(),
)

@Serializable
data class ZUserSum(
    val username: String? = null,
    val sum: Int = 0,
    val storage: Double = 0.0,
    val views: Int = 0,
)

@Serializable
data class ZUrlUserSum(val username: String? = null, val sum: Int = 0, val views: Int = 0)

@Serializable
data class ZTypeSum(val type: String = "", val sum: Int = 0)

@Serializable
data class ZMetricPoint(
    val id: String = "",
    val createdAt: String? = null,
    val users: Int = 0,
    val files: Int = 0,
    val fileViews: Int = 0,
    val urls: Int = 0,
    val urlViews: Int = 0,
    /** Prisma BigInt: may arrive as a number or a string, so keep it textual and parse on use. */
    val storage: String? = null,
)

// ---------- invites: /api/auth/invites ----------

@Serializable
data class ZInvite(
    val id: String,
    val code: String,
    val createdAt: String? = null,
    val expiresAt: String? = null,
    val uses: Int = 0,
    val maxUses: Int? = null,
    val inviter: ZInviter? = null,
)

@Serializable
data class ZInviter(val id: String = "", val username: String = "", val role: String = "USER")

@Serializable
data class CreateInviteBody(val expiresAt: String? = null, val maxUses: Int? = null)

/** POST /api/user/folders - note the body says `isPublic` while the GET shape says `public`. */
@Serializable
data class CreateFolderBody(
    val name: String,
    val isPublic: Boolean = false,
    /** Nests the new folder under an existing one; E4007 if it is not yours. */
    val parentId: String? = null,
)

/** POST /api/user/urls - optionals must be absent, not null (zod .optional() rejects null). */
@Serializable
data class CreateUrlBody(val destination: String, val vanity: String? = null)

/** POST /api/users - admin only; role must not outrank the creator (E3008). */
@Serializable
data class CreateUserBody(val username: String, val password: String, val role: String = "USER")

/** PATCH /api/user/folders/{id}. Only the changed fields are sent. */
@Serializable
data class PatchFolderBody(
    val name: String? = null,
    val isPublic: Boolean? = null,
    val allowUploads: Boolean? = null,
)

/**
 * DELETE /api/user/folders/{id}.
 *
 * `delete` selects what is being removed - the folder itself, or one file from it.
 * `childrenAction` decides what happens to the contents: "root" lifts them out and keeps them,
 * "cascade-files" deletes them along with the folder.
 *
 * [delete] has NO default on purpose. The serializer drops any property equal to its declared
 * default, so `= "folder"` meant the field never reached the server and it answered
 * `E1000 body/delete Invalid option: expected one of file|folder`.
 */
@Serializable
data class DeleteFolderBody(
    val delete: String,
    val childrenAction: String? = null,
    val id: String? = null,
)

/** PATCH /api/users/{id} - admin only. */
@Serializable
data class PatchUserBody(
    val username: String? = null,
    val password: String? = null,
    val role: String? = null,
    val quota: QuotaBody? = null,
)

@Serializable
data class QuotaBody(
    val filesType: String? = null,
    val maxFiles: Int? = null,
    val maxBytes: String? = null,
    val maxUrls: Int? = null,
)

/** DELETE /api/users/{id}. [delete] true also removes everything they uploaded. */
@Serializable
data class DeleteUserBody(val delete: Boolean)

// ---------- username/password login ----------

/** POST /api/auth/login. [code] carries the TOTP digits on the second attempt. */
@Serializable
data class LoginBody(val username: String, val password: String, val code: String? = null)

/**
 * Login does NOT return an API token - it opens a server-side session and sets a cookie.
 * When the account has TOTP enabled and no code was sent, the server answers `{"totp": true}`
 * instead of a user, which is the signal to ask for the six digits and retry.
 *
 * The user object the server also returns is deliberately NOT modelled: nothing here needs it,
 * and parsing it made an otherwise successful login fail whenever that shape differed at all.
 * Only the field that changes control flow is declared.
 */
@Serializable
data class LoginResponse(val totp: Boolean = false)

/**
 * POST /api/auth/register. [code] is the invite code; leaving it out asks for open registration,
 * which most servers have disabled (E1037). Null is dropped by the serializer, which is exactly
 * the distinction the server makes between "invited" and "open" signup.
 */
@Serializable
data class RegisterBody(val username: String, val password: String, val code: String? = null)

/** GET /api/user/token, authenticated by the login session cookie. */
@Serializable
data class TokenResponse(val token: String)

// ---------- own account ----------

/**
 * PATCH /api/user - editing yourself, unlike PatchUserBody which is the admin route.
 *
 * [currentPassword] is required whenever [password] is set (E1067 otherwise, E1066 if wrong).
 * [avatar] is a data URL; clearing it needs a literal JSON null, which `explicitNulls = false`
 * would drop - see the raw call in AccountViewModel.
 */
@Serializable
data class PatchMeBody(
    val username: String? = null,
    val password: String? = null,
    val currentPassword: String? = null,
    val avatar: String? = null,
    val view: ZView? = null,
)

/** One logged-in device. [current] is not sent by the server - the list it arrives in says so. */
@Serializable
data class ZSession(
    val id: String,
    val client: String? = null,
    val device: String? = null,
    val createdAt: String? = null,
)

/** GET/DELETE /api/user/sessions. The current session is always kept out of [other]. */
@Serializable
data class SessionsResponse(
    val current: ZSession? = null,
    val other: List<ZSession> = emptyList(),
)

/**
 * DELETE /api/user/sessions. Either one [sessionId] or [all] others - the server refuses to
 * delete the session you are using (E1021).
 */
@Serializable
data class DeleteSessionBody(val sessionId: String? = null, val all: Boolean? = null)

// ---------- two-factor (TOTP) ----------

/**
 * GET /api/user/mfa/totp.
 *
 * [qrcode] is a ready-made `data:image/png;base64,...` and is only sent while TOTP is *not* yet
 * enabled - the server withholds it once a secret is active. That makes its presence the reliable
 * "is 2FA on?" signal, so nothing here has to trust a separate user field.
 */
@Serializable
data class TotpSetup(val secret: String, val qrcode: String? = null)

/** POST /api/user/mfa/totp - the secret is echoed back with the code that proves it works. */
@Serializable
data class EnableTotpBody(val code: String, val secret: String)

/** DELETE /api/user/mfa/totp - carries a body, so the call is declared with `hasBody = true`. */
@Serializable
data class DisableTotpBody(val code: String)

// ---------- server actions ----------

@Serializable
data class StatusResponse(val status: String? = null)

@Serializable
data class ZeroFiles(val files: List<ZeroFile> = emptyList())

@Serializable
data class ZeroFile(val id: String, val name: String)

@Serializable
data class RequerySizeBody(val forceDelete: Boolean = false, val forceUpdate: Boolean = false)

@Serializable
data class ThumbnailsBody(val rerun: Boolean = false)

// ---------- server settings ----------

/**
 * The settings object has ~100 keys and grows between Zipline versions, so it is kept as raw JSON
 * and rendered generically rather than mirrored field by field.
 */
@Serializable
data class ServerSettingsResponse(
    val settings: JsonObject = JsonObject(emptyMap()),
    val tampered: List<String> = emptyList(),
)

/** GET /api/user/stats - the numbers behind the dashboard stat cards. */
@Serializable
data class UserStats(
    val filesUploaded: Int = 0,
    val favoriteFiles: Int = 0,
    val views: Int = 0,
    val avgViews: Double = 0.0,
    val storageUsed: Double = 0.0,
    val avgStorageUsed: Double = 0.0,
    val urlsCreated: Int = 0,
    val urlViews: Int = 0,
    val sortTypeCount: Map<String, Int> = emptyMap(),
)
