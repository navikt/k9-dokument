package no.nav.helse

import com.github.tomakehurst.wiremock.WireMockServer
import com.typesafe.config.ConfigFactory
import io.ktor.config.*
import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.*
import no.nav.helse.dusseldorf.ktor.core.fromResources
import no.nav.helse.dusseldorf.testsupport.jws.Azure
import no.nav.helse.dusseldorf.testsupport.wiremock.WireMockBuilder
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class K9DokumentMedNettyTest {

    private companion object {

        private val logger: Logger = LoggerFactory.getLogger(K9DokumentMedNettyTest::class.java)

        private const val eier = "290990123456"

        val baseUrl = "http://localhost:8888/v1/dokument"

        val httpClient: CloseableHttpClient = HttpClients.createDefault()

        private val wireMockServer: WireMockServer = WireMockBuilder()
            .withAzureSupport()
            .k9DokumentConfiguration()
            .build()
            .stubVirusScan()

        private fun getTokenV1() = Azure.V1_0.generateJwt(clientId = "hva-som-helst", audience = "k9-dokument")
        private fun getTokenV2() = Azure.V2_0.generateJwt(clientId = "hva-som-helst", audience = "k9-dokument")

        private val s3 = S3()


        @BeforeAll
        @JvmStatic
        fun buildUp() {
            CollectorRegistry.defaultRegistry.clear()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            logger.info("Tearing down")
            wireMockServer.stop()
            s3.stop()
            logger.info("Tear down complete")
        }
    }

    @Test
    fun `lagring, henting og sletting fungerer`() = withNettyEngine() {
        val token = getTokenV1()

        httpClient.lasteOppDokumentJson(
            url = baseUrl,
            token =token,
            fileContent = "iPhone_6.jpg".fromResources().readBytes(),
            fileName = "iPhone_6.jpg",
            tittel = "Bilde av en iphone",
            contentType = "image/jpeg",
            eier = eier
        ).apply {
            assertEquals(HttpStatusCode.Created.value, this.statusLine.statusCode)
            val url = this.getFirstHeader("location").value
            assertNotNull(url)
            close()

            httpClient.hentDokument("$url?eier=$eier", token).apply {
                assertEquals(200, statusLine.statusCode)
                close()
            }

            httpClient.slettDokument("$url?eier=$eier", token).apply {
                assertEquals(204, statusLine.statusCode)
                close()
            }

            httpClient.hentDokument("$url?eier=$eier", token).apply {
                assertEquals(404, statusLine.statusCode)
                close()
            }
        }
    }

    fun getConfig(testConfiguration: Map<String, String>): ApplicationConfig {
        val fileConfig = ConfigFactory.load().withoutPath("ktor")
        val testConfig = ConfigFactory.parseMap(
            testConfiguration
        )
        val mergedConfig = testConfig.withFallback(fileConfig)
        return HoconApplicationConfig(mergedConfig)
    }


    private fun withNettyEngine(applicationPort: Int = 8888, block: suspend () -> Unit) {
        val testConfiguration = TestConfiguration.asMap(
            port = applicationPort,
            wireMockServer = wireMockServer,
            s3 = s3,
            konfigurerAzure = true,
            s3ExpiryInDays = null
        ).plus("ktor.application.id" to "k9-dokument-netty-test")

        val server = embeddedServer(Netty, applicationEngineEnvironment {
            config = getConfig(testConfiguration)
            module { k9Dokument() }
            connector { port = applicationPort }
        })
        val job = GlobalScope.launch {
            server.start(wait = true)
        }

        runBlocking {
            for (i in 1..20) {
                delay(1000L)
                val response = try {
                    httpClient.execute(HttpGet("http://localhost:$applicationPort/isready"))
                } catch (e: IOException) {
                    logger.warn("Server not ready yet...")
                    null
                }
                if (response != null && response.statusLine.statusCode == 200) {
                    break
                }
            }
        }

        try {
            runBlocking { block() }
        } finally {
            server.stop(1000, 1000)
            runBlocking { job.cancelAndJoin() }
        }
    }

    internal fun CloseableHttpClient.lasteOppDokumentJson(
        url: String,
        token: String,
        fileName: String = "iPhone_6.jpg",
        fileContent: ByteArray = fileName.fromResources().readBytes(),
        tittel: String = "En eller annen tittel",
        contentType: String = if (fileName.endsWith("pdf")) "application/pdf" else "image/jpeg",
        eier: String? = null
    ): CloseableHttpResponse {
        val base64encodedContent = Base64.getEncoder().encodeToString(fileContent)

        val httpPost = HttpPost("$url?eier=$eier").apply {
            addHeader(HttpHeaders.Authorization, "Bearer $token")
            addHeader(HttpHeaders.ContentType, "application/json")
            addHeader(HttpHeaders.XCorrelationId, "laster-opp-doument-ok-json")
            entity = StringEntity(
                """
            {
                "content" : "$base64encodedContent",
                "content_type": "$contentType",
                "title" : "$tittel"
            }
            """.trimIndent()
            )
        }

        return httpClient.execute(httpPost)!!
    }

    private fun CloseableHttpClient.hentDokument(
        path: String,
        token: String
    ): CloseableHttpResponse {
        val httpGetFile = HttpGet(path).apply {
            addHeader(HttpHeaders.Authorization, "Bearer $token")
            addHeader(HttpHeaders.XCorrelationId, "henter-dokument-ok")
        }

        return execute(httpGetFile)
    }


    private fun CloseableHttpClient.slettDokument(path: String, token: String): CloseableHttpResponse {
        val httpDelete = HttpDelete(path).apply {
            addHeader(HttpHeaders.Authorization, "Bearer $token")
            addHeader(HttpHeaders.XCorrelationId, "sletter-dokument-ok")
        }
        return execute(httpDelete)
    }
}
