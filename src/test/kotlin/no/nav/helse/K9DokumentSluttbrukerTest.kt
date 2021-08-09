package no.nav.helse

import com.github.tomakehurst.wiremock.WireMockServer
import com.typesafe.config.ConfigFactory
import io.ktor.config.ApplicationConfig
import io.ktor.config.HoconApplicationConfig
import io.ktor.http.*
import io.ktor.server.testing.TestApplicationEngine
import io.ktor.server.testing.contentType
import io.ktor.server.testing.createTestEnvironment
import io.ktor.server.testing.handleRequest
import io.prometheus.client.CollectorRegistry
import no.nav.helse.dusseldorf.ktor.core.fromResources
import no.nav.helse.dusseldorf.testsupport.jws.LoginService
import no.nav.helse.dusseldorf.testsupport.jws.NaisSts
import no.nav.helse.dusseldorf.testsupport.wiremock.WireMockBuilder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class K9DokumentSluttbrukerTest {

    private companion object {
        private val logger: Logger = LoggerFactory.getLogger(K9DokumentSluttbrukerTest::class.java)

        private val wireMockServer: WireMockServer = WireMockBuilder()
            .withLoginServiceSupport()
            .k9DokumentConfiguration()
            .build()
            .stubVirusScan()

        private val s3 = S3()


        fun getConfig(): ApplicationConfig {
            val fileConfig = ConfigFactory.load()
            val testConfig = ConfigFactory.parseMap(
                TestConfiguration.asMap(
                    wireMockServer = wireMockServer,
                    s3 = s3,
                    konfigurerLoginService = true,
                    s3ExpiryInDays = 1
                )
            )
            val mergedConfig = testConfig.withFallback(fileConfig)
            return HoconApplicationConfig(mergedConfig)
        }

        val engine = TestApplicationEngine(createTestEnvironment {
            config = getConfig()
        })


        @BeforeAll
        @JvmStatic
        fun buildUp() {
            CollectorRegistry.defaultRegistry.clear()
            engine.start(wait = true)
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
    fun `test isready, isalive og metrics`() {
        with(engine) {
            handleRequest(HttpMethod.Get, "/isready") {}.apply {
                assertEquals(HttpStatusCode.OK, response.status())
                handleRequest(HttpMethod.Get, "/isalive") {}.apply {
                    assertEquals(HttpStatusCode.OK, response.status())
                    handleRequest(HttpMethod.Get, "/metrics") {}.apply {
                        assertEquals(HttpStatusCode.OK, response.status())
                        handleRequest(HttpMethod.Get, "/health") {}.apply {
                            assertEquals(HttpStatusCode.OK, response.status())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `request med sluttbruker ID-Token fungerer`() {
        val fnr = "29099912345"

        val idToken = LoginService.V1_0.generateJwt(fnr)

        val url = engine.lasteOppDokumentMultipart(
            token = idToken,
            fileName = "test.pdf",
            tittel = "PDF"
        )

        val path = Url(url).fullPath

        with(engine) {
            handleRequest(HttpMethod.Get, path) {
                addHeader(HttpHeaders.Authorization, "Bearer $idToken")
                addHeader(HttpHeaders.XCorrelationId, "henter-dokument-som-sluttbruker")
            }.apply {
                assertEquals(HttpStatusCode.OK, response.status())
                assertEquals("application/pdf", response.contentType().toString())
            }
        }
    }

    @Test
    fun `request med sluttbruker ID-Token for annen bruker fungerer ikke`() {
        val fnrLagre = "29099012345"
        val fnrHente = "29099067891"

        val idTokenLagre = LoginService.V1_0.generateJwt(fnrLagre)
        val idTokenHente = LoginService.V1_0.generateJwt(fnrHente)

        val url = engine.lasteOppDokumentMultipart(
            token = idTokenLagre,
            fileName = "test.pdf",
            tittel = "PDF"
        )

        val path = Url(url).fullPath

        with(engine) {
            handleRequest(HttpMethod.Get, path) {
                addHeader(HttpHeaders.Authorization, "Bearer $idTokenHente")
                addHeader(HttpHeaders.XCorrelationId, "henter-dokument-som-feil-sluttbruker")
            }.apply {
                assertEquals(HttpStatusCode.NotFound, response.status())
            }
        }
    }

    @Test
    fun `request med sluttbruker ID-Token token utstedt av en annen issuer feiler`() {
        val fnr = "29099012345"
        val idToken = LoginService.V1_0.generateJwt(issuer = "http://localhost:8080/en-anne-issuer", fnr = fnr)
        with(engine) {
            handleRequest(HttpMethod.Get, "/v1/dokument/1234567") {
                addHeader(HttpHeaders.Authorization, "Bearer $idToken")
                addHeader(HttpHeaders.XCorrelationId, "cc32e953-746e-4fb0-84f7-9e5a7e10bbe1")
            }.apply {
                assertEquals(HttpStatusCode.Unauthorized, response.status())
            }
        }
    }

    @Test
    fun `request uten token feiler`() {
        with(engine) {
            handleRequest(HttpMethod.Get, "/v1/dokument/123456789") {
                addHeader(HttpHeaders.XCorrelationId, "cc32e953-746e-4fb0-84f7-9e5a7e10bbe1")
            }.apply {
                assertEquals(HttpStatusCode.Unauthorized, response.status())
            }
        }
    }

    @Test
    fun `request med sluttbruker ID-Token paa for lav level feiler`() {
        val fnr = "29099012345"
        val idToken = LoginService.V1_0.generateJwt(level = 3, fnr = fnr)
        with(engine) {
            handleRequest(HttpMethod.Get, "/v1/dokument/1234567") {
                addHeader(HttpHeaders.Authorization, "Bearer $idToken")
                addHeader(HttpHeaders.XCorrelationId, "cc32e953-746e-4fb0-84f7-9e5a7e10bbe1")
            }.apply {
                assertEquals(HttpStatusCode.Forbidden, response.status())
            }
        }
    }

    @Test
    fun `lagring med sluttbruker ID-Token og uthenting med System Access Token feiler`() {
        val fnrLagre = "29099012345"

        val idTokenLagre = LoginService.V1_0.generateJwt(fnrLagre)
        val accessTokenHente = NaisSts.generateJwt(application = "srvpps-mottak")
            //NaisSts.generateJwt(application = "srvpps-mottak")

        val url = engine.lasteOppDokumentMultipart(
            token = idTokenLagre,
            fileName = "test.pdf",
            tittel = "PDF"
        )

        val path = "${Url(url).fullPath}?eier=$fnrLagre"

        with(engine) {
            handleRequest(HttpMethod.Get, path) {
                addHeader(HttpHeaders.Authorization, "Bearer $accessTokenHente")
                addHeader(HttpHeaders.XCorrelationId, "cc32e953-746e-4fb0-84f7-9e5a7e10bbe1")
            }.apply {
                assertEquals(HttpStatusCode.Unauthorized, response.status())
            }
        }
    }

    @Test
    fun `opplasting av virus feiler`() {
        val fnr = "29099912345"

        val idToken = LoginService.V1_0.generateJwt(fnr)

        with(engine) {
            val liksomVirus = "iPhone_6_infected.jpg".fromResources().readBytes()

            lasteOppDokumentJson(
                token = idToken,
                fileContent = liksomVirus,
                fileName = "liksom_virus.jpg",
                tittel = "Et liksom virus",
                contentType = "image/jpeg",
                expectedHttpStatusCode = HttpStatusCode.InternalServerError
            )
        }
    }

    @Test
    fun `Lagring og overskriving og henting med Custom Dokument ID`() {
        val path = "/v1/dokument/customized/test1"

        CustomDokumentIdUtils.sluttbrukerLagreOgHent(
            engine = engine,
            json = """
                {
                    "json": 1
                }
            """.trimIndent(),
            path = path
        )

        CustomDokumentIdUtils.sluttbrukerLagreOgHent(
            engine = engine,
            json = """
                {
                    "json": 2,
                    "ny": {
                        "overskriv": true
                    }
                }
            """.trimIndent(),
            path = path
        )

        // En som ikke er eier får 404
        CustomDokumentIdUtils.sluttbrukerHent(
            engine = engine,
            path = path,
            token = LoginService.V1_0.generateJwt("29098912345"),
            expectedHttpStatus = HttpStatusCode.NotFound
        )
    }

    @Test
    fun `Hente dokment som ikke finnes med Custom Dokument ID`() {
        val path = "/v1/dokument/customized/test2"
        CustomDokumentIdUtils.sluttbrukerHent(
            engine = engine,
            path = path,
            expectedHttpStatus = HttpStatusCode.NotFound
        )
    }

}

