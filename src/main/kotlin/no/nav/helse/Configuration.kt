package no.nav.helse

import io.ktor.config.ApplicationConfig
import io.ktor.util.KtorExperimentalAPI
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URL

private val logger: Logger = LoggerFactory.getLogger("nav.Configuration")

@KtorExperimentalAPI
data class Configuration(private val config : ApplicationConfig) {
    private fun getString(key: String,
                          secret: Boolean = false) : String  {
        val stringValue = config.property(key).getString()
        logger.info("{}={}", key, if (secret) "***" else stringValue)
        return stringValue
    }

    private fun <T>getListFromCsv(key: String,
                                  secret: Boolean = false,
                                  builder: (value: String) -> T) : List<T> {
        val csv = getString(key, false)
        val list = csv.replace(" ", "").split(",")
        val builtList = mutableListOf<T>()
        list.forEach { entry ->
            logger.info("$key entry = ${if (secret) "***" else entry}")
            builtList.add(builder(entry))
        }
        return builtList.toList()
    }

    fun getAuthorizedSystemsForRestApi(): List<String> {
        return getListFromCsv(
            key = "nav.rest_api.authorized_systems",
            builder = { value -> value}
        )
    }

    fun getServiceAccountJwksUrl() : URL {
        return URL(getString("nav.authorization.service_account.jwks_url"))
    }

    fun getServiceAccountIssuer() : String {
        return getString("nav.authorization.service_account.issuer")
    }

    fun getEndUserJwksUrl() : URL {
        return URL(getString("nav.authorization.end_user.jwks_url"))
    }

    fun getEndUserIssuer() : String {
        return getString("nav.authorization.end_user.issuer")
    }

    fun logIndirectlyUsedConfiguration() {
        logger.info("# Indirectly used configuration")
        val properties = System.getProperties()
        logger.info("## System Properties")
        properties.forEach { key, value ->
            if (key is String && (key.startsWith(prefix = "http", ignoreCase = true) || key.startsWith(prefix = "https", ignoreCase = true))) {
                logger.info("$key=$value")
            }
        }
        logger.info("## Environment variables")
        val environmentVariables = System.getenv()
        logger.info("HTTP_PROXY=${environmentVariables["HTTP_PROXY"]}")
        logger.info("HTTPS_PROXY=${environmentVariables["HTTPS_PROXY"]}")
        logger.info("NO_PROXY=${environmentVariables["NO_PROXY"]}")
    }
}