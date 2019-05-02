package no.nav.helse

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import io.ktor.config.ApplicationConfig
import io.ktor.util.KtorExperimentalAPI
import no.nav.helse.dusseldorf.ktor.core.getOptionalList
import no.nav.helse.dusseldorf.ktor.core.getOptionalString
import no.nav.helse.dusseldorf.ktor.core.getRequiredString
import java.net.URL

private const val CRYPTO_PASSPHRASE_PREFIX = "CRYPTO_PASSPHRASE_"

@KtorExperimentalAPI
data class Configuration(private val config : ApplicationConfig) {

    private fun getCryptoPasshrase(key: String) : String {
        val configValue = config.getOptionalString(key = key, secret = true)
        if (configValue != null) return configValue
        return System.getenv(key) ?: throw IllegalStateException("Environment Variable $key må være satt")
    }

    fun getEncryptionPassphrase() : Pair<Int, String> {
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

    fun getAuthorizedSubjects(): List<String> {
        return config.getOptionalList(
            key = "nav.authorization.authorized_subjects",
            builder = { value -> value},
            secret = false
        )
    }

    fun getJwksUrl() : URL = URL(config.getRequiredString("nav.authorization.jwks_url", secret = false))
    fun getIssuer() : String = config.getRequiredString("nav.authorization.issuer", secret = false)

    private fun getS3AccessKey() : String = config.getRequiredString("nav.storage.s3.access_key", secret = true)
    private fun getS3SecretKey() : String = config.getRequiredString("nav.storage.s3.secret_key", secret = true)
    private fun getS3SigningRegion() : String = config.getRequiredString("nav.storage.s3.signing_region", secret = false)
    private fun getS3ServiceEndpoint() : String = config.getRequiredString("nav.storage.s3.service_endpoint", secret = false)
    fun getS3Configured() : AmazonS3 {
        return AmazonS3ClientBuilder.standard()
            .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(getS3ServiceEndpoint(), getS3SigningRegion()))
            .enablePathStyleAccess()
            .withCredentials(AWSStaticCredentialsProvider(BasicAWSCredentials(getS3AccessKey(), getS3SecretKey())))
            .build()
    }
    fun getS3ExpirationInDays() : Int? = config.getOptionalString("nav.storage.s3.expiration_in_days", secret = false)?.toInt()

    fun enableVirusScan() : Boolean = config.getRequiredString("nav.virus_scan.enabled", false).equals("true", true)
    fun getVirusScanUrl() : URL = URL(config.getRequiredString("nav.virus_scan.url", secret = false))
}