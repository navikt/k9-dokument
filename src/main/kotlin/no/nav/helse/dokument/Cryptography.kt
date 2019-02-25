package no.nav.helse.dokument

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import no.nav.helse.aktoer.AktoerId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.IllegalStateException

private val logger: Logger = LoggerFactory.getLogger("nav.Cryptography")

class Cryptography(
    private val encryptionPassphrase: Pair<Int, String>,
    private val decryptionPassphrases: Map<Int, String>
) {

    init {
        logger.info("Genererer ID'er med Nøkkel ID = ${encryptionPassphrase.first}")
        logger.info("Decrypterer med ${decryptionPassphrases.size} mulige Nøkkel ID'er:")
        decryptionPassphrases.forEach{ logger.info("${it.key}")}
    }

    fun encrypt(id: String,
                plainText : String,
                aktoerId: AktoerId
    ) : String {
        logger.trace("Krypterer ID $id")
        val keyId = extractKeyId(id)
        logger.trace("Krypterer med Nøkkel ID $keyId")

        return Crypto(
            passphrase = getPasshrase(keyId),
            iv = aktoerId.id
        ).encrypt(plainText)
    }

    fun decrypt(id: String,
                encrypted: String,
                aktoerId: AktoerId
    ) : String {
        logger.trace("Decrypterer ID $id")
        val keyId = extractKeyId(id)
        logger.trace("Dekrypterer med Nøkkel ID $keyId")

        return Crypto(
            passphrase = getPasshrase(keyId),
            iv = aktoerId.id
        ).decrypt(encrypted)
    }

    fun id() : String {
        val jwt = JWT.create()
            .withKeyId(encryptionPassphrase.first.toString())
            .withJWTId(UUID.randomUUID().toString())
            .sign(Algorithm.none())
            .removeSuffix(".")
        logger.trace("Genrerert ID er $jwt")
        if (logger.isTraceEnabled) {
            logger.trace("Decoded ID er ${decodeId(jwt)}")
        }
        return jwt
    }

    private fun decodeId(id: String) = JWT.decode(if(id.endsWith(".")) id else "$id.")

    private fun extractKeyId(id: String) = decodeId(id).keyId.toInt()

    private fun getPasshrase(keyId: Int) : String {
        if (!decryptionPassphrases.containsKey(keyId)) {
            throw IllegalStateException("Har inget passord tilgjengelig for Nøkkel ID $keyId. Får ikke gjort encrypt/decrypt.")
        }
        return decryptionPassphrases[keyId]!!
    }
}