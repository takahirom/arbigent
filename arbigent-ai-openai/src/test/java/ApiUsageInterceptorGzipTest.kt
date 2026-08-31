import com.sun.net.httpserver.HttpServer
import io.github.takahirom.arbigent.API_USAGE_PEEK_BYTE_LIMIT
import io.github.takahirom.arbigent.ArbigentFiles
import io.github.takahirom.arbigent.ArbigentInternalApi
import io.github.takahirom.arbigent.parseApiUsageRecord
import io.github.takahirom.arbigent.writeApiUsageRecord
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * Proves the shape of the problem the recording code has to survive: a network interceptor sits
 * below OkHttp's transparent gzip, so it is handed compressed bytes, and reading them must not
 * stop the caller from reading the body afterwards.
 */
@OptIn(ArbigentInternalApi::class)
class ApiUsageInterceptorGzipTest {

  private val responseBody =
    """{"model":"test-model","usage":{"prompt_tokens":42,"completion_tokens":7,"total_tokens":49}}"""

  @Test
  fun recordsUsageFromAGzippedResponseAndLeavesTheBodyReadable() {
    val usagesDir = Files.createTempDirectory("usages").toFile()
    ArbigentFiles.usagesDir = usagesDir

    var peekedBytes: ByteArray? = null

    // The interceptor is a copy of the production one, kept to the same two calls it makes.
    val recording = Interceptor { chain ->
      val response = chain.proceed(chain.request())
      runCatching {
        val bytes = response.peekBody(API_USAGE_PEEK_BYTE_LIMIT).bytes()
        peekedBytes = bytes
        parseApiUsageRecord(
          requestUuid = chain.request().url.queryParameter("requestUuid"),
          responseBytes = bytes,
          contentEncoding = response.header("Content-Encoding")
        )?.let(::writeApiUsageRecord)
      }
      response
    }

    val server = gzipServer()
    try {
      val client = OkHttpClient.Builder().addNetworkInterceptor(recording).build()
      val url = "http://localhost:${server.address.port}/v1/chat/completions?requestUuid=uuid-1"
      val body = client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }

      // The caller still sees the decompressed body, so peekBody did not consume anything.
      assertEquals(responseBody, body)

      // The point of the decoding step: what the interceptor is handed is gzip, not JSON. Without
      // decoding, parsing these bytes finds no usage and nothing is recorded at all.
      val peeked = requireNotNull(peekedBytes)
      assertEquals(0x1f, peeked[0].toInt() and 0xff)
      assertEquals(0x8b, peeked[1].toInt() and 0xff)

      val files = usagesDir.listFiles().orEmpty()
      assertEquals(1, files.size)
      val written = files.single().readText()
      assertTrue(written, written.contains("\"input_tokens\":42"))
      assertTrue(written, written.contains("\"output_tokens\":7"))
      assertTrue(written, written.contains("\"request_uuid\":\"uuid-1\""))
    } finally {
      server.stop(0)
    }
  }

  /** Serves a gzip encoded body, the way the real endpoint does when OkHttp asks for gzip. */
  private fun gzipServer(): HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
    createContext("/") { exchange ->
      val gzipped = ByteArrayOutputStream()
        .also { out -> GZIPOutputStream(out).use { it.write(responseBody.toByteArray()) } }
        .toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.responseHeaders.add("Content-Encoding", "gzip")
      exchange.sendResponseHeaders(200, gzipped.size.toLong())
      exchange.responseBody.use { it.write(gzipped) }
    }
    start()
  }
}
