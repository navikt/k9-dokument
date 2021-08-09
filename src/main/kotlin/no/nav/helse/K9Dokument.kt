package no.nav.helse

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.PropertyNamingStrategy
import io.ktor.application.*
import io.ktor.auth.Authentication
import io.ktor.auth.authenticate
import io.ktor.features.*
import io.ktor.jackson.jackson
import io.ktor.metrics.micrometer.MicrometerMetrics
import io.ktor.routing.Routing
import io.prometheus.client.hotspot.DefaultExports
import no.nav.helse.dokument.crypto.Cryptography
import no.nav.helse.dokument.DokumentService
import no.nav.helse.dokument.VirusScanner
import no.nav.helse.dokument.storage.S3Storage
import no.nav.helse.dokument.storage.S3StorageHealthCheck
import no.nav.helse.dokument.api.*
import no.nav.helse.dokument.eier.EierResolver
import no.nav.helse.dusseldorf.ktor.auth.*
import no.nav.helse.dusseldorf.ktor.core.*
import no.nav.helse.dusseldorf.ktor.health.HealthRoute
import no.nav.helse.dusseldorf.ktor.health.HealthService
import no.nav.helse.dusseldorf.ktor.jackson.JacksonStatusPages
import no.nav.helse.dusseldorf.ktor.jackson.dusseldorfConfigured
import no.nav.helse.dusseldorf.ktor.metrics.MetricsRoute
import no.nav.helse.dusseldorf.ktor.metrics.init
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val logger: Logger = LoggerFactory.getLogger("nav.K9Dokument")

fun main(args: Array<String>): Unit  = io.ktor.server.netty.EngineMain.main(args)

fun Application.k9Dokument() {
    val appId = environment.config.id()
    logProxyProperties()
    DefaultExports.initialize()

    val configuration = Configuration(environment.config)
    val issuers = configuration.issuers()

    install(Authentication) {
        multipleJwtIssuers(issuers)
    }

    install(ContentNegotiation) {
        jackson {
            k9DokumentConfigured()
        }
    }

    install(StatusPages) {
        DefaultStatusPages()
        JacksonStatusPages()
        AuthStatusPages()
    }

    val s3Storage = S3Storage(
        s3 = configuration.getS3Configured(),
        expirationInDays = configuration.getS3ExpirationInDays()
    )

    install(CallIdRequired)

    val dokumentService = DokumentService(
        cryptography = Cryptography(
            encryptionPassphrase = configuration.getEncryptionPassphrase(),
            decryptionPassphrases = configuration.getDecryptionPassphrases()
        ),
        storage = s3Storage,
        virusScanner = getVirusScanner(configuration)
    )

    val eierResolver = EierResolver(
        hentEierFra = configuration.hentEierFra()
    )

    val contentTypeService = ContentTypeService()

    install(Routing) {
        authenticate(*issuers.allIssuers()) {
            requiresCallId {
                dokumentV1Apis(
                    dokumentService = dokumentService,
                    eierResolver = eierResolver,
                    contentTypeService = contentTypeService,
                    baseUrl = configuration.getBaseUrl()
                )
                customIdV1Apis(
                    dokumentService = dokumentService,
                    eierResolver = eierResolver,
                    contentTypeService = contentTypeService,
                    støtterExpirationFraRequest = configuration.støtterExpirationFraRequest()
                )
            }
        }

        DefaultProbeRoutes()
        MetricsRoute()
        HealthRoute(
            healthService = HealthService(
                setOf(
                    S3StorageHealthCheck(
                        s3Storage = s3Storage
                    )
                )
            )
        )
    }

    install(MicrometerMetrics) {
        init(appId)
    }

    install(CallId) {
        fromXCorrelationIdHeader()
    }

    intercept(ApplicationCallPipeline.Monitoring) {
        call.request.log(templateQueryParameters = true)
    }

    install(CallLogging) {
        correlationIdAndRequestIdInMdc()
        logRequests(templateQueryParameters = true)
    }
}

private fun getVirusScanner(config: Configuration) : VirusScanner? {
    if (!config.enableVirusScan()) return null
    return VirusScanner(url = config.getVirusScanUrl())
}

internal fun ObjectMapper.k9DokumentConfigured() = dusseldorfConfigured()
    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)