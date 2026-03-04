package org.monogram.data.datasource.remote

import org.monogram.core.DispatcherProvider
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class HttpExternalProxyDataSource(
    private val dispatchers: DispatcherProvider
) : ExternalProxyDataSource {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun fetchProxyUrls(): List<String> = withContext(dispatchers.io) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("https://api.telega.info/v1/auth/proxy")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DAHL-Mobile-App")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "gzip")
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext emptyList()

            val stream = if ("gzip".equals(connection.contentEncoding, ignoreCase = true)) {
                GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }

            json.decodeFromString<ProxyResponse>(stream.bufferedReader().use { it.readText() }).proxies
        } catch (e: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }
}
@Serializable
@InternalSerializationApi
private data class ProxyResponse(val proxies: List<String> = emptyList())