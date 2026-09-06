package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Serves a fake arbigent distribution so the wrapper can be exercised end to end without
 * downloading the real ~175 MB release.
 */
class WrapperCommandTest {
  private lateinit var server: HttpServer
  private lateinit var workDir: File
  private lateinit var userHome: File
  private lateinit var archive: File
  private var servedSha256: String = ""

  private val baseUrl get() = "http://127.0.0.1:${server.address.port}"
  private val distributionUrl get() = "$baseUrl/arbigent-0.0.0.tar.gz"

  @BeforeTest
  fun setUp() {
    workDir = createTempDir("arbigentw-work")
    userHome = createTempDir("arbigentw-home")
    archive = buildFakeDistribution()
    servedSha256 = sha256Of(archive)
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/0.0.0/arbigent-0.0.0.tar.gz") { exchange ->
        val bytes = archive.readBytes()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      createContext("/0.0.0/arbigent-0.0.0.tar.gz.sha256") { exchange ->
        val bytes = "$servedSha256\n".toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      createContext("/arbigent-0.0.0.tar.gz") { exchange ->
        val bytes = archive.readBytes()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      createContext("/arbigent-0.0.0.tar.gz.sha256") { exchange ->
        val bytes = "$servedSha256\n".toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      // Serves the checksum through a redirect that leaves the encrypted connection.
      createContext("/redirect/") { exchange ->
        exchange.responseHeaders.add("Location", "http://example.invalid/arbigent-0.0.0.tar.gz.sha256")
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
      }
      start()
    }
  }

  @AfterTest
  fun tearDown() {
    server.stop(0)
    workDir.deleteRecursively()
    userHome.deleteRecursively()
    archive.parentFile.deleteRecursively()
  }

  @Test
  fun `the wrapper template is valid POSIX shell`() {
    val template = File(workDir, "template.sh")
    template.writeBytes(wrapperTemplateBytes())
    val result = runProcess(listOf("sh", "-n", template.absolutePath), workDir, emptyMap())
    assertEquals(0, result.exitCode, result.output)
  }

  @Test
  fun `generating the wrapper pins the served checksum`() {
    generateWrapper()

    val properties = File(workDir, ".arbigent/wrapper/arbigent-wrapper.properties").readText()
    assertContains(properties, "distributionVersion=0.0.0")
    assertContains(properties, "distributionUrl=$distributionUrl")
    assertContains(properties, "distributionSha256Sum=$servedSha256")
    assertTrue(File(workDir, "arbigentw").canExecute(), "arbigentw must be executable")
  }

  @Test
  fun `the wrapper installs the distribution and forwards its arguments`() {
    generateWrapper()

    val result = runWrapper(listOf("run", "--scenario-ids=example"))

    assertEquals(0, result.exitCode, result.output)
    assertContains(result.output, "fake-arbigent run --scenario-ids=example")
  }

  @Test
  fun `an installed distribution is reused without the server`() {
    generateWrapper()
    assertEquals(0, runWrapper(listOf("first")).exitCode)
    server.stop(0)

    val result = runWrapper(listOf("second"))

    assertEquals(0, result.exitCode, result.output)
    assertContains(result.output, "fake-arbigent second")
  }

  @Test
  fun `a checksum mismatch fails and installs nothing`() {
    generateWrapper()
    val propertiesFile = File(workDir, ".arbigent/wrapper/arbigent-wrapper.properties")
    propertiesFile.writeText(
      propertiesFile.readText().replace(servedSha256, "0".repeat(64))
    )

    val result = runWrapper(listOf("run"))

    assertFalse(result.exitCode == 0, "the wrapper must reject a mismatching archive")
    assertContains(result.output, "checksum mismatch")
    val installed = File(userHome, "wrapper/dists").walkTopDown().filter { it.name == "arbigent" }
    assertEquals(emptyList(), installed.toList(), "no launcher may be published after a mismatch")
  }

  @Test
  fun `an unsupported Java version is reported`() {
    generateWrapper()
    val fakeBin = File(workDir, "fake-bin").apply { mkdirs() }
    File(fakeBin, "java").apply {
      writeText("#!/bin/sh\necho 'openjdk version \"11.0.22\" 2024-01-16' >&2\n")
      setExecutable(true)
    }

    val result = runWrapper(listOf("run"), extraPath = fakeBin.absolutePath)

    assertEquals(1, result.exitCode, result.output)
    assertContains(result.output, "Java 17 or later is required")
  }

  @Test
  fun `a missing Java installation is reported`() {
    generateWrapper()
    val minimalBin = File(workDir, "minimal-bin").apply { mkdirs() }
    // Only the utilities the wrapper needs before it looks for Java.
    listOf("sh", "sed", "tr", "tail", "cut", "basename", "dirname").forEach { tool ->
      val resolved = runProcess(listOf("sh", "-c", "command -v $tool"), workDir, emptyMap()).output.trim()
      // Symlink rather than copy: a copied macOS system binary loses its signature and is killed.
      if (resolved.isNotEmpty()) {
        java.nio.file.Files.createSymbolicLink(File(minimalBin, tool).toPath(), File(resolved).toPath())
      }
    }

    val result = runWrapper(listOf("run"), path = minimalBin.absolutePath)

    assertEquals(1, result.exitCode, result.output)
    assertContains(result.output, "no Java found")
  }

  @Test
  fun `the wrapper can pin a release without an installed arbigent`() {
    generateWrapper()
    File(workDir, ".arbigent/wrapper/arbigent-wrapper.properties").delete()

    val result = runWrapper(
      listOf("bootstrapped"),
      extraEnvironment = mapOf(
        "ARBIGENT_VERSION" to "0.0.0",
        "ARBIGENT_RELEASE_BASE_URL" to baseUrl,
      ),
    )

    assertEquals(0, result.exitCode, result.output)
    assertContains(result.output, "fake-arbigent bootstrapped")
    val properties = File(workDir, ".arbigent/wrapper/arbigent-wrapper.properties").readText()
    assertContains(properties, "distributionSha256Sum=$servedSha256")
  }

  @Test
  fun `a missing properties file without a version is reported`() {
    generateWrapper()
    File(workDir, ".arbigent/wrapper/arbigent-wrapper.properties").delete()

    val result = runWrapper(listOf("run"))

    assertEquals(1, result.exitCode, result.output)
    assertContains(result.output, "ARBIGENT_VERSION")
  }

  @Test
  fun `the install directory is named by the whole digest`() {
    generateWrapper()

    assertEquals(0, runWrapper(listOf("run")).exitCode)

    // A truncated digest would let two distributions that share the prefix share one install, so
    // the directory carries all 64 characters.
    val distRoot = File(userHome, "wrapper/dists/arbigent-0.0.0")
    assertEquals(listOf(servedSha256), distRoot.list()?.sorted())
  }

  @Test
  fun `an invalid JAVA_HOME is reported instead of silently using another Java`() {
    generateWrapper()

    val result = runWrapper(
      listOf("run"),
      extraEnvironment = mapOf("JAVA_HOME" to File(workDir, "no-such-jdk").absolutePath),
    )

    assertEquals(1, result.exitCode, result.output)
    assertContains(result.output, "JAVA_HOME is set to an invalid directory")
  }

  @Test
  fun `pinning a release leaves no partial properties file behind`() {
    generateWrapper()
    val propertiesDir = File(workDir, ".arbigent/wrapper")
    File(propertiesDir, "arbigent-wrapper.properties").delete()

    val result = runWrapper(
      listOf("bootstrapped"),
      extraEnvironment = mapOf(
        "ARBIGENT_VERSION" to "0.0.0",
        "ARBIGENT_RELEASE_BASE_URL" to baseUrl,
      ),
    )

    assertEquals(0, result.exitCode, result.output)
    // The properties file is written through a temp file in the same directory and renamed, so the
    // only file left is the finished one.
    assertEquals(listOf("arbigent-wrapper.properties"), propertiesDir.list()?.sorted())
  }

  @Test
  fun `a checksum served over plain HTTP is refused`() {
    val result = ArbigentWrapperCommand().test(
      listOf(
        "--version", "0.0.0",
        "--distribution-url", "http://example.invalid/arbigent-0.0.0.tar.gz",
        "--dir", workDir.absolutePath,
      )
    )

    assertFalse(result.statusCode == 0, result.output)
    assertContains(result.stderr, "must be served over HTTPS")
    assertEquals(emptyList(), workDir.list()?.sorted())
  }

  @Test
  fun `a checksum redirected off HTTPS is refused`() {
    val result = ArbigentWrapperCommand().test(
      listOf(
        "--version", "0.0.0",
        "--distribution-url", "$baseUrl/redirect/arbigent-0.0.0.tar.gz",
        "--dir", workDir.absolutePath,
      )
    )

    assertFalse(result.statusCode == 0, result.output)
    assertContains(result.stderr, "must be served over HTTPS")
  }

  @Test
  fun `a symbolic link in the wrapper directory is refused`() {
    val elsewhere = File(workDir, "elsewhere").apply { mkdirs() }
    java.nio.file.Files.createSymbolicLink(File(workDir, ".arbigent").toPath(), elsewhere.toPath())

    val result = ArbigentWrapperCommand().test(
      listOf(
        "--version", "0.0.0",
        "--distribution-url", distributionUrl,
        "--sha256", servedSha256,
        "--dir", workDir.absolutePath,
      )
    )

    assertFalse(result.statusCode == 0, result.output)
    assertContains(result.stderr, "is a symbolic link")
    assertEquals(emptyList(), elsewhere.list()?.sorted(), "nothing may be written through the link")
    assertEquals(listOf(".arbigent", "elsewhere"), workDir.list()?.sorted())
  }

  @Test
  fun `generating the wrapper leaves no staged files behind`() {
    generateWrapper()

    assertEquals(listOf(".arbigent", "arbigentw"), workDir.list()?.sorted())
    assertEquals(
      listOf("arbigent-wrapper.properties"),
      File(workDir, ".arbigent/wrapper").list()?.sorted(),
    )
  }

  @Test
  fun `pinning a release through a redirect off HTTPS is refused`() {
    generateWrapper()
    File(workDir, ".arbigent/wrapper/arbigent-wrapper.properties").delete()

    val result = runWrapper(
      listOf("bootstrapped"),
      extraEnvironment = mapOf(
        "ARBIGENT_VERSION" to "0.0.0",
        "ARBIGENT_RELEASE_BASE_URL" to "$baseUrl/redirect",
      ),
    )

    assertEquals(1, result.exitCode, result.output)
    // curl refuses the downgrade itself: 'Protocol "http" disabled (in redirect)'.
    assertContains(result.output, "in redirect")
    assertContains(result.output, "cannot read")
    assertFalse(
      File(workDir, ".arbigent/wrapper/arbigent-wrapper.properties").exists(),
      "an unverifiable digest must not be pinned",
    )
  }

  @Test
  fun `pinning a release over plain HTTP is refused`() {
    generateWrapper()
    File(workDir, ".arbigent/wrapper/arbigent-wrapper.properties").delete()

    val result = runWrapper(
      listOf("bootstrapped"),
      extraEnvironment = mapOf(
        "ARBIGENT_VERSION" to "0.0.0",
        "ARBIGENT_RELEASE_BASE_URL" to "http://example.invalid",
      ),
    )

    assertEquals(1, result.exitCode, result.output)
    assertContains(result.output, "unencrypted connection")
  }

  private fun generateWrapper() {
    val result = ArbigentWrapperCommand().test(
      listOf("--version", "0.0.0", "--distribution-url", distributionUrl, "--dir", workDir.absolutePath)
    )
    assertEquals(0, result.statusCode, result.output + result.stderr)
  }

  private fun runWrapper(
    args: List<String>,
    path: String? = null,
    extraPath: String? = null,
    extraEnvironment: Map<String, String> = emptyMap(),
  ): ProcessResult {
    val resolvedPath = path ?: listOfNotNull(extraPath, System.getenv("PATH")).joinToString(":")
    val environment = mutableMapOf(
      "ARBIGENT_USER_HOME" to userHome.absolutePath,
      "HOME" to userHome.absolutePath,
      "PATH" to resolvedPath,
    )
    environment.putAll(extraEnvironment)
    return runProcess(
      listOf("sh", File(workDir, "arbigentw").absolutePath) + args,
      workDir,
      environment,
      clearEnvironment = true,
    )
  }

  private fun buildFakeDistribution(): File {
    val stagingDir = createTempDir("arbigentw-dist")
    val binDir = File(stagingDir, "arbigent-0.0.0/bin").apply { mkdirs() }
    File(binDir, "arbigent").apply {
      writeText("#!/bin/sh\necho \"fake-arbigent $*\"\n")
      setExecutable(true)
    }
    val tarball = File(stagingDir, "arbigent-0.0.0.tar.gz")
    val result = runProcess(
      listOf("tar", "-czf", tarball.absolutePath, "-C", stagingDir.absolutePath, "arbigent-0.0.0"),
      stagingDir,
      emptyMap(),
    )
    check(result.exitCode == 0) { "cannot build the fake distribution: ${result.output}" }
    return tarball
  }

  private fun wrapperTemplateBytes(): ByteArray =
    checkNotNull(javaClass.getResourceAsStream("/arbigentw")) { "arbigentw template is missing" }
      .use { it.readBytes() }

  private fun sha256Of(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes())
      .joinToString("") { "%02x".format(it) }

  private fun createTempDir(prefix: String): File =
    java.nio.file.Files.createTempDirectory(prefix).toFile()

  private data class ProcessResult(val exitCode: Int, val output: String)

  private fun runProcess(
    command: List<String>,
    directory: File,
    environment: Map<String, String>,
    clearEnvironment: Boolean = false,
  ): ProcessResult {
    val outputFile = File.createTempFile("arbigentw-output", ".txt")
    try {
      val builder = ProcessBuilder(command)
        .directory(directory)
        .redirectErrorStream(true)
        .redirectOutput(outputFile)
      if (clearEnvironment) builder.environment().clear()
      builder.environment().putAll(environment)
      val process = builder.start()
      // Wait first: reading the output before the process ends can block forever.
      if (!process.waitFor(120, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return ProcessResult(-1, "timed out: ${outputFile.readText()}")
      }
      return ProcessResult(process.exitValue(), outputFile.readText())
    } finally {
      outputFile.delete()
    }
  }
}
