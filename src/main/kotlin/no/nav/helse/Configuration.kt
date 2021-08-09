package no.nav.helse

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import io.ktor.config.ApplicationConfig
import no.nav.helse.dokument.eier.HentEierFra
import no.nav.helse.dusseldorf.ktor.auth.*
import no.nav.helse.dusseldorf.ktor.core.getOptionalList
import no.nav.helse.dusseldorf.ktor.core.getOptionalString
import no.nav.helse.dusseldorf.ktor.core.getRequiredString
import java.net.URI

internal data class Configuration(private val config : ApplicationConfig) {

    private companion object {
        private const val CRYPTO_PASSPHRASE_PREFIX = "CRYPTO_PASSPHRASE_"
        private const val S3_HTTP_REQUEST_TIMEOUT = 3 * 1000
        private const val S3_HTTP_REQUEST_RETIRES = 3

        private const val LOGIN_SERVICE_V1_ALIAS = "login-service-v1"
        private const val LOGIN_SERVICE_V2_ALIAS = "login-service-v2"
        private const val AZURE_V1 = "azure-v1"
        private const val AZURE_V2 = "azure-v2"
    }

    private val issuers = config.issuers().withAdditionalClaimRules(
        mapOf(
            LOGIN_SERVICE_V1_ALIAS to setOf(EnforceEqualsOrContains("acr", "Level4"))
        )
    )

    // Crypto
    private fun getCryptoPasshrase(key: String) : String {
        val configValue = config.getOptionalString(key = key, secret = true)
        if (configValue != null) return configValue
        return System.getenv(key) ?: throw IllegalStateException("Environment Variable $key må være satt")
    }
    internal fun getEncryptionPassphrase() : Pair<Int, String> {
        val identifier = config.getRequiredString("nav.crypto.passphrase.encryption_identifier", secret = false).toInt()
        val passphrase = getCryptoPasshrase("$CRYPTO_PASSPHRASE_PREFIX$identifier")
        return Pair(identifier, passphrase)
    }
    fun getDecryptionPassphrases() : Map<Int, String> {
        val identifiers = config.getOptionalList( // Kan være kun den vi krypterer med
            key = "nav.crypto.passphrase.decryption_identifiers",
            builder = { value -> value.toInt()},
            secret = false
        )

        val decryptionPassphrases = mutableMapOf<Int, String>()
        identifiers.forEach { decryptionPassphrases[it] = getCryptoPasshrase("$CRYPTO_PASSPHRASE_PREFIX$it") }
        val encryptionPassphrase = getEncryptionPassphrase()
        decryptionPassphrases[encryptionPassphrase.first] = encryptionPassphrase.second // Forsikre oss om at nåværende krypterings-ID alltid er en av decrypterings-ID'ene
        return decryptionPassphrases.toMap()
    }

    // Auth
    private fun isLoginServiceConfigured() = issuers.filterKeys { LOGIN_SERVICE_V1_ALIAS == it.alias() || LOGIN_SERVICE_V2_ALIAS == it.alias() }.isNotEmpty()
    private fun isAzureConfigured() = issuers.filterKeys { AZURE_V1 == it.alias() || AZURE_V2 == it.alias() }.isNotEmpty()
    internal fun issuers() : Map<Issuer, Set<ClaimRule>> {
        if (isLoginServiceConfigured() && isAzureConfigured()) {
            throw IllegalStateException("Både azure og loginService kan ikke være konfigurert samtidig.")
        }
        if (issuers.isEmpty()) throw IllegalStateException("Må konfigureres minst en issuer.")
        return issuers
    }
    internal fun hentEierFra(): HentEierFra {
        if (isLoginServiceConfigured()) return HentEierFra.ACCESS_TOKEN_SUB_CLAIM
        if (isAzureConfigured()) return HentEierFra.QUERY_PARAMETER_EIER
        else throw IllegalStateException("Kunne ikke hvor eier kunne hentes fra. Sjekk konfigurerte isuers.")
    }

    // S3
    private fun getS3AccessKey() : String = config.getRequiredString("nav.storage.s3.access_key", secret = true)
    private fun getS3SecretKey() : String = config.getRequiredString("nav.storage.s3.secret_key", secret = true)
    private fun getS3SigningRegion() : String = config.getRequiredString("nav.storage.s3.signing_region", secret = false)
    private fun getS3ServiceEndpoint() : String = config.getRequiredString("nav.storage.s3.service_endpoint", secret = false)
    internal fun getS3Configured() : AmazonS3 {
        return AmazonS3ClientBuilder.standard()
            .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(getS3ServiceEndpoint(), getS3SigningRegion()))
            .enablePathStyleAccess()
            .withCredentials(AWSStaticCredentialsProvider(BasicAWSCredentials(getS3AccessKey(), getS3SecretKey())))
            .withClientConfiguration(
                ClientConfiguration()
                    .withRequestTimeout(S3_HTTP_REQUEST_TIMEOUT)
                    .withMaxErrorRetry(S3_HTTP_REQUEST_RETIRES)
            )
            .build()
    }
    internal fun getS3ExpirationInDays() : Int? = config.getOptionalString("nav.storage.s3.expiration_in_days", secret = false)?.toInt()
    internal fun støtterExpirationFraRequest() = getS3ExpirationInDays() == null

    // Virus Scan
    internal fun enableVirusScan() : Boolean = config.getRequiredString("nav.virus_scan.enabled", false).equals("true", true)
    internal fun getVirusScanUrl() = URI(config.getRequiredString("nav.virus_scan.url", secret = false))

    // URL's
    internal fun getBaseUrl() : String = config.getRequiredString("nav.base_url", secret = false)
}
