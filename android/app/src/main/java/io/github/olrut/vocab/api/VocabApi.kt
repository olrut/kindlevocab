package io.github.olrut.vocab.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The backend API (docs/SPEC.md § 3), as far as the app uses it. */
interface VocabApi {
    @GET("users")
    suspend fun users(): List<User>

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @POST("auth/logout")
    suspend fun logout()

    @GET("words")
    suspend fun words(@Query("status") status: String): List<Word>

    @PATCH("words/{id}")
    suspend fun setStatus(@Path("id") id: Long, @Body body: StatusPatch): Word

    @GET("audio/{id}")
    suspend fun audio(@Path("id") id: Long): ResponseBody
}

@Serializable
data class User(val id: Long, val name: String, val hasPin: Boolean)

@Serializable
data class LoginRequest(val userId: Long, val pin: String? = null, val device: String)

@Serializable
data class LoginResponse(val token: String, val user: LoggedInUser)

@Serializable
data class LoggedInUser(val id: Long, val name: String)

@Serializable
data class StatusPatch(val status: String)

/** The fields of the server's word JSON that the review screen needs. */
@Serializable
data class Word(
    val id: Long,
    val word: String,
    val definition: String,
    val example: String? = null,
    val finnish: String? = null,
    val ipa: String? = null,
    val hasAudio: Boolean = false,
    val status: String = "learning",
)

private val json = Json {
    ignoreUnknownKeys = true
    // Leave a null PIN out of the login body rather than sending "pin": null.
    explicitNulls = false
}

/**
 * Retrofit client for `baseUrl` (from [apiBaseUrl]). Sends the bearer token when there is one;
 * a 401 on a request that carried a token means the session is gone, so [onUnauthorized] runs.
 */
fun createApi(baseUrl: String, token: () -> String?, onUnauthorized: () -> Unit): VocabApi {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val t = token()
            val request = if (t == null) chain.request() else chain.request().newBuilder().header("Authorization", "Bearer $t").build()
            val response = chain.proceed(request)
            if (response.code == 401 && t != null) onUnauthorized()
            response
        }
        .build()
    return Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(VocabApi::class.java)
}

/**
 * The API base URL for what the user typed as the server address, or null if it is not one.
 * "vocab.tail1234.ts.net" → "https://vocab.tail1234.ts.net/api/". A trailing /api is accepted.
 */
fun apiBaseUrl(input: String): String? {
    val s = input.trim()
    if (s.isEmpty() || s.contains(' ')) return null
    val withScheme = if (s.contains("://")) s else "https://$s"
    if (!withScheme.startsWith("http://") && !withScheme.startsWith("https://")) return null
    val url = withScheme.toHttpUrlOrNull() ?: return null
    val path = url.encodedPath.trimEnd('/').removeSuffix("/api")
    return url.newBuilder().encodedPath("$path/api/").query(null).fragment(null).build().toString()
}

/** A message for the user from a failed API call. */
fun errorMessage(e: Throwable): String = when (e) {
    is HttpException -> e.response()?.errorBody()?.string()?.let(::serverError) ?: "HTTP ${e.code()}"
    is IOException -> "Cannot reach the server"
    else -> e.message ?: e.toString()
}

private fun serverError(body: String): String? =
    runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
