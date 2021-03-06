package no.nav.helse

import com.github.tomakehurst.wiremock.WireMockServer
import no.nav.helse.dusseldorf.testsupport.wiremock.getAzureV1WellKnownUrl
import no.nav.helse.dusseldorf.testsupport.wiremock.getAzureV2WellKnownUrl
import no.nav.helse.dusseldorf.testsupport.wiremock.getLoginServiceV1WellKnownUrl

internal object TestConfiguration {

    internal fun asMap(
        wireMockServer: WireMockServer? = null,
        s3: S3? = null,
        port : Int = 8080,
        virusScanUrl : String? = wireMockServer?.getVirusScanUrl(),
        passphrase1 : String = "password",
        passphrase2 : String = "oldpassword",
        passphrase3: String = "reallyoldpassword",

        s3ServiceEndpoint : String? = s3?.getServiceEndpoint(),
        s3SigningRegion : String? = s3?.getSigningRegion(),
        s3ExpiryInDays : Int?,

        konfigurerAzure: Boolean = false,
        konfigurerLoginService: Boolean = false,
        k9DokumentAzureClientId: String = "k9-dokument"
    ) : Map<String, String> {
        val map =  mutableMapOf(
            Pair("ktor.deployment.port","$port"),
            Pair("nav.crypto.passphrase.encryption_identifier", "1"),
            Pair("nav.crypto.passphrase.decryption_identifiers", "2,3"),
            Pair("CRYPTO_PASSPHRASE_1",passphrase1),
            Pair("CRYPTO_PASSPHRASE_2",passphrase2),
            Pair("CRYPTO_PASSPHRASE_3",passphrase3),
            Pair("nav.storage.s3.service_endpoint", "$s3ServiceEndpoint"),
            Pair("nav.storage.s3.signing_region", "$s3SigningRegion"),
            Pair("nav.virus_scan.url", "$virusScanUrl"),
            Pair("nav.base_url", "http://localhost:$port")
        )

        // S3
        if (s3ExpiryInDays != null) {
            map["nav.storage.s3.expiration_in_days"] = "$s3ExpiryInDays"
        }
        if (!s3?.secretKey.isNullOrEmpty()) {
            map["nav.storage.s3.secret_key"] = s3!!.getSecretKey()
        }
        if (!s3?.accessKey.isNullOrEmpty()) {
            map["nav.storage.s3.access_key"] = s3!!.getAccessKey()
        }

        // Issuers
        if (wireMockServer != null && konfigurerLoginService) {
            map["nav.auth.issuers.0.alias"] = "login-service-v1"
            map["nav.auth.issuers.0.discovery_endpoint"] = wireMockServer.getLoginServiceV1WellKnownUrl()
        }
        else if (wireMockServer != null && konfigurerAzure) {
            map["nav.auth.issuers.1.type"] = "azure"
            map["nav.auth.issuers.1.alias"] = "azure-v1"
            map["nav.auth.issuers.1.discovery_endpoint"] = wireMockServer.getAzureV1WellKnownUrl()
            map["nav.auth.issuers.1.audience"] = k9DokumentAzureClientId
            map["nav.auth.issuers.1.azure.require_certificate_client_authentication"] = "true"
            map["nav.auth.issuers.1.azure.required_roles"] = "access_as_application"

            map["nav.auth.issuers.2.type"] = "azure"
            map["nav.auth.issuers.2.alias"] = "azure-v2"
            map["nav.auth.issuers.2.discovery_endpoint"] = wireMockServer.getAzureV2WellKnownUrl()
            map["nav.auth.issuers.2.audience"] = k9DokumentAzureClientId
            map["nav.auth.issuers.2.azure.require_certificate_client_authentication"] = "true"
            map["nav.auth.issuers.2.azure.required_roles"] = "access_as_application"
        }

        return map.toMap()
    }
}