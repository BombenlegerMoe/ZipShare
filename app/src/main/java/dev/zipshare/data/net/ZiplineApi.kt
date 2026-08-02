package dev.zipshare.data.net

import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.HeaderMap
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** No Kotlin default arguments here — Retrofit cannot see through the synthetic $default bridge. */
interface ZiplineApi {

    @GET("api/healthcheck")
    suspend fun healthcheck(): Response<HealthcheckResponse>

    @GET("api/version")
    suspend fun version(): Response<VersionResponse>

    @GET("api/user")
    suspend fun user(): Response<UserResponse>

    /**
     * Opens a session; the token is fetched separately with [tokenForSession]. Unauthenticated,
     * so this is the one call made before a profile has a token.
     */
    @POST("api/auth/login")
    suspend fun login(@Body body: LoginBody): Response<LoginResponse>

    /**
     * Creates an account from an invite code and, like [login], leaves a session cookie behind -
     * so registration finishes the same way sign-in does, via [tokenForSession].
     *
     * The returned user object is deliberately not modelled; nothing here reads it, and parsing
     * it could only ever turn a successful registration into a failure.
     */
    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterBody): Response<JsonObject>

    /** Exchanges the login session cookie for the account's API token. */
    @GET("api/user/token")
    suspend fun tokenForSession(): Response<TokenResponse>

    /** Edits your own account: username, password, avatar, view-route settings. */
    @PATCH("api/user")
    suspend fun patchMe(@Body body: PatchMeBody): Response<JsonObject>

    /** Same call, for the one field that must go out as an explicit JSON null (avatar removal). */
    @PATCH("api/user")
    suspend fun patchMeRaw(@Body body: JsonObject): Response<JsonObject>

    @GET("api/user/sessions")
    suspend fun sessions(): Response<SessionsResponse>

    /** DELETE with a body; @DELETE cannot carry one. Responds with the remaining sessions. */
    @HTTP(method = "DELETE", path = "api/user/sessions", hasBody = true)
    suspend fun deleteSession(@Body body: DeleteSessionBody): Response<SessionsResponse>

    /** Current TOTP secret, plus a server-rendered QR while 2FA is still off. */
    @GET("api/user/mfa/totp")
    suspend fun totpSetup(): Response<TotpSetup>

    @POST("api/user/mfa/totp")
    suspend fun enableTotp(@Body body: EnableTotpBody): Response<JsonObject>

    /** DELETE with a body - Retrofit needs @HTTP for that, @DELETE cannot carry one. */
    @HTTP(method = "DELETE", path = "api/user/mfa/totp", hasBody = true)
    suspend fun disableTotp(@Body body: DisableTotpBody): Response<JsonObject>

    /**
     * Regenerates the account's token, invalidating the old one everywhere. The response carries
     * the replacement, which must be written back into the profile or the app locks itself out.
     */
    @PATCH("api/user/token")
    suspend fun refreshToken(): Response<TokenResponse>

    /**
     * The account avatar. Returned as a bare `data:image/...;base64,...` string rather than JSON
     * or image bytes, so it is read as plain text. Only needed when /api/user omits the field.
     */
    @GET("api/user/avatar")
    suspend fun avatar(): Response<ResponseBody>

    @GET("api/user/folders")
    suspend fun folders(@Query("noincl") noIncludeFiles: Boolean): Response<List<ZFolder>>

    @POST("api/user/folders")
    suspend fun createFolder(@Body body: CreateFolderBody): Response<ZFolder>

    @PATCH("api/user/folders/{id}")
    suspend fun patchFolder(@Path("id") id: String, @Body body: PatchFolderBody): Response<ZFolder>

    /** Body-carrying DELETE, so the caller can say what happens to the contents. */
    @HTTP(method = "DELETE", path = "api/user/folders/{id}", hasBody = true)
    suspend fun deleteFolder(
        @Path("id") id: String,
        @Body body: DeleteFolderBody,
    ): Response<JsonObject>

    @GET("api/user/files")
    suspend fun files(
        @Query("page") page: Int,
        @Query("perpage") perPage: Int,
        @Query("sortBy") sortBy: String,
        @Query("order") order: String,
        @Query("folder") folder: String?,
        @Query("searchField") searchField: String?,
        @Query("searchQuery") searchQuery: String?,
        @Query("favorite") favorite: Boolean?,
    ): Response<FilesPage>

    @GET("api/user/urls")
    suspend fun urls(): Response<List<ZUrl>>

    @POST("api/user/urls")
    suspend fun createUrl(@Body body: CreateUrlBody): Response<ZUrl>

    /** Admin only; a non-admin token gets E3000. */
    @GET("api/users")
    suspend fun users(): Response<List<ZLimitedUser>>

    /** Admin only. */
    @POST("api/users")
    suspend fun createUser(@Body body: CreateUserBody): Response<ZLimitedUser>

    @PATCH("api/users/{id}")
    suspend fun patchUser(@Path("id") id: String, @Body body: PatchUserBody): Response<ZLimitedUser>

    /** Body-carrying DELETE: `delete` also removes the account's files and links. */
    @HTTP(method = "DELETE", path = "api/users/{id}", hasBody = true)
    suspend fun deleteUser(@Path("id") id: String, @Body body: DeleteUserBody): Response<JsonObject>

    @DELETE("api/user/urls/{id}")
    suspend fun deleteUrl(@Path("id") id: String): Response<ZUrl>

    /** Dashboard "Recent files". Server clamps take to 1..100 (default 3). */
    @GET("api/user/recent")
    suspend fun recent(@Query("take") take: Int): Response<List<ZFile>>

    /** Dashboard stat cards. */
    @GET("api/user/stats")
    suspend fun stats(): Response<UserStats>

    @DELETE("api/user/files/{id}")
    suspend fun deleteFile(@Path("id") id: String): Response<ZFile>

    @PATCH("api/user/files/{id}")
    suspend fun patchFile(@Path("id") id: String, @Body body: PatchFileBody): Response<ZFile>

    /**
     * Same endpoint, raw body. Needed because the converter is configured with
     * `explicitNulls = false`, which drops a null `password` - and JSON null is exactly how the
     * server is told to REMOVE a password. A typed body can express "unchanged" but not "clear".
     */
    @PATCH("api/user/files/{id}")
    suspend fun patchFileRaw(@Path("id") id: String, @Body body: JsonObject): Response<ZFile>

    /** Bulk favourite / move. */
    @PATCH("api/user/files/transaction")
    suspend fun bulkPatchFiles(@Body body: BulkPatchBody): Response<JsonObject>

    /** Bulk delete. HTTP DELETE with a body, which Retrofit needs told explicitly. */
    @HTTP(method = "DELETE", path = "api/user/files/transaction", hasBody = true)
    suspend fun bulkDeleteFiles(@Body body: BulkDeleteBody): Response<JsonObject>

    // ---------- tags ----------

    @GET("api/user/tags")
    suspend fun tags(): Response<List<ZTag>>

    @POST("api/user/tags")
    suspend fun createTag(@Body body: CreateTagBody): Response<ZTag>

    @PATCH("api/user/tags/{id}")
    suspend fun patchTag(@Path("id") id: String, @Body body: PatchTagBody): Response<ZTag>

    /** Returns `{success:true}` rather than the tag, so the response shape is left loose. */
    @DELETE("api/user/tags/{id}")
    suspend fun deleteTag(@Path("id") id: String): Response<JsonObject>

    // ---------- instance metrics ----------

    /** E3001 when metrics are disabled, E3000 when the instance restricts them to admins. */
    @GET("api/stats")
    suspend fun instanceStats(@Query("all") all: Boolean): Response<ApiStats>

    // ---------- invites (admin) ----------

    @GET("api/auth/invites")
    suspend fun invites(): Response<List<ZInvite>>

    @POST("api/auth/invites")
    suspend fun createInvite(@Body body: CreateInviteBody): Response<ZInvite>

    @DELETE("api/auth/invites/{id}")
    suspend fun deleteInvite(@Path("id") id: String): Response<ZInvite>

    // ---------- server actions (admin) ----------

    @DELETE("api/server/clear_temp")
    suspend fun clearTemp(): Response<StatusResponse>

    /** Lists the zero-byte candidates so the user can see what a clear would remove. */
    @GET("api/server/clear_zeros")
    suspend fun zeroFiles(): Response<ZeroFiles>

    @DELETE("api/server/clear_zeros")
    suspend fun clearZeros(): Response<StatusResponse>

    @POST("api/server/requery_size")
    suspend fun requerySize(@Body body: RequerySizeBody): Response<StatusResponse>

    @POST("api/server/thumbnails")
    suspend fun generateThumbnails(@Body body: ThumbnailsBody): Response<StatusResponse>

    // ---------- server settings (superadmin) ----------

    @GET("api/server/settings")
    suspend fun serverSettings(): Response<ServerSettingsResponse>

    @PATCH("api/server/settings")
    suspend fun patchServerSettings(@Body body: JsonObject): Response<ServerSettingsResponse>

    @Multipart
    @POST("api/upload")
    suspend fun upload(
        @HeaderMap headers: Map<String, String>,
        @Part parts: List<MultipartBody.Part>,
    ): Response<UploadResponse>

    @Multipart
    @POST("api/upload/partial")
    suspend fun uploadPartial(
        @HeaderMap headers: Map<String, String>,
        @Part part: MultipartBody.Part,
    ): Response<UploadResponse>
}
