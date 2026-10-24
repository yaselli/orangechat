package me.rerere.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DeepSeekSearchTest {
    private val original = SearchService.httpClient
    private val options = SearchServiceOptions.DeepSeekOptions(apiKey = "test-key")
    @After fun restore() { SearchService.httpClient = original }
    private fun mock(body: String, status: Int = 200, inspect: (Request) -> Unit = {}) {
        SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    }
    @Test fun searchAuthenticatesClampsCountAndPreservesProviderOptionSerialization() = runBlocking {
        mock("""{"results":[{"title":"title","url":"https://example.com","summary":"long summary","snippet":"short"}]}""") { request ->
            assertEquals("/v1/tools/search", request.url.encodedPath)
            assertEquals("Bearer test-key", request.header("Authorization"))
            val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals(50, body["count"]!!.jsonPrimitive.int)
        }
        val result = DeepSeekSearchService.search(buildJsonObject { put("query", "hello") }, SearchCommonOptions(99), options).getOrThrow()
        assertEquals("long summary", result.items.single().text)
        val encoded = Json.encodeToString<SearchServiceOptions>(options)
        assertEquals(options, Json.decodeFromString<SearchServiceOptions>(encoded))
        assertTrue(SearchService.getService(options) === DeepSeekSearchService)
    }
    @Test fun fetchMapsMarkdownAndValidatesUrlBeforeNetwork() = runBlocking {
        mock("""{"title":"Page","content":"# Markdown"}""") { assertEquals("/v1/tools/fetch", it.url.encodedPath) }
        val result = DeepSeekSearchService.scrape(buildJsonObject { put("url", "https://example.com/page") }, SearchCommonOptions(), options).getOrThrow()
        assertEquals("# Markdown", result.urls.single().content)
        assertEquals("Page", result.urls.single().metadata!!.title)
        for (url in listOf("file:///etc/hosts", "https://user:password@example.com")) {
            assertTrue(DeepSeekSearchService.scrape(buildJsonObject { put("url", url) }, SearchCommonOptions(), options).isFailure)
        }
    }
    @Test fun errorResponsesPropagateAndBlankOrOverlongQueriesAreRejected() = runBlocking {
        mock("""{"error":{"message":"invalid key"}}""", 401)
        val failure = DeepSeekSearchService.search(buildJsonObject { put("query", "q") }, SearchCommonOptions(), options)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("401"))
        mock("""{"results":[]}""") { error("invalid queries must not make requests") }
        for (query in listOf("  ", "x".repeat(129))) {
            assertTrue(DeepSeekSearchService.search(buildJsonObject { put("query", query) }, SearchCommonOptions(), options).isFailure)
        }
    }
}
