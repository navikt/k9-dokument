package no.nav.helse

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import io.ktor.application.*
import io.ktor.auth.Authentication
import io.ktor.auth.authenticate
import io.ktor.auth.jwt.JWTPrincipal
import io.ktor.auth.jwt.jwt
import io.ktor.features.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.jackson.jackson
import io.ktor.request.header
import io.ktor.response.header
import io.ktor.routing.Routing
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.hotspot.DefaultExports
import no.nav.helse.dokument.Cryptography
import no.nav.helse.dokument.DokumentService
import no.nav.helse.dokument.api.*
import no.nav.helse.dusseldorf.ktor.core.id
import no.nav.helse.dusseldorf.ktor.core.logProxyProperties
import no.nav.helse.validering.valideringStatusPages
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeUnit

private val logger: Logger = LoggerFactory.getLogger("nav.PleiepengerDokument")
private const val GENERATED_REQUEST_ID_PREFIX = "generated-"
private const val REALM = "pleiepenger-dokument"

fun main(args: Array<String>): Unit  = io.ktor.server.netty.EngineMain.main(args)

@KtorExperimentalAPI
fun Application.pleiepengerDokument() {
    val appId = environment.config.id()
    logProxyProperties()
    DefaultExports.initialize()

    val configuration = Configuration(environment.config)

    val authorizedSubjects = configuration.getAuthorizedSubjects()

    install(Authentication) {
        val issuer = configuration.getIssuer()
        val jwksProvider = JwkProviderBuilder(configuration.getJwksUrl()).buildConfigured()

        jwt {
            verifier(jwksProvider, issuer)
            realm = REALM
            validate { credentials ->
                when {
                    authorizedSubjects.isEmpty() -> {
                        logger.trace("Authorized: Ettersom alle subject kan benytte tjenesten.")
                        return@validate JWTPrincipal(credentials.payload)
                    }
                    credentials.payload.subject in authorizedSubjects -> {
                        logger.trace("Authorized : ${credentials.payload.subject} er i listen [${authorizedSubjects.joinToString()}]")
                        return@validate JWTPrincipal(credentials.payload)
                    }
                    else -> {
                        logger.warn("Unauthorized : ${credentials.payload.subject} er ikke i listen [${authorizedSubjects.joinToString()}]")
                        return@validate null
                    }
                }
            }
        }
    }

    install(ContentNegotiation) {
        jackson {
            ObjectMapper.server(this)
        }
    }

    install(StatusPages) {
        defaultStatusPages()
        valideringStatusPages()
        metadataStatusPages()
    }

    val s3Storage = S3Storage(
        s3 = configuration.getS3Configured(),
        expirationInDays = configuration.getS3ExpirationInDays()
    )

    install(Routing) {
        authenticate {
            dokumentV1Apis(
                dokumentService = DokumentService(
                    cryptography = Cryptography(
                        encryptionPassphrase = configuration.getEncryptionPassphrase(),
                        decryptionPassphrases = configuration.getDecryptionPassphrases()
                    ),
                    storage = s3Storage,
                    objectMapper = ObjectMapper.server()
                ),
                eierResolver = EierResolver(
                    authorizedSubjects = authorizedSubjects
                ),
                contentTypeService = ContentTypeService()
            )
        }

        monitoring(
            collectorRegistry = CollectorRegistry.defaultRegistry,
            s3Storage = s3Storage,
            pingUrls = mapOf(
                Pair(configuration.getJwksUrl(), HttpStatusCode.OK)
            )
        )
    }

    install(CallId) {
        header(HttpHeaders.XCorrelationId)
    }

    install(CallLogging) {
        callIdMdc("correlation_id")
        mdc("request_id") { call ->
            val requestId = call.request.header(HttpHeaders.XRequestId)?.removePrefix(GENERATED_REQUEST_ID_PREFIX) ?: "$GENERATED_REQUEST_ID_PREFIX${UUID.randomUUID()}"
            call.response.header(HttpHeaders.XRequestId, requestId)
            requestId
        }
    }
}

private fun JwkProviderBuilder.buildConfigured() : JwkProvider {
    cached(10, 24, TimeUnit.HOURS)
    rateLimited(10, 1, TimeUnit.MINUTES)
    return build()
}