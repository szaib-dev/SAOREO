package com.codetic.saoreo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** The identity key signs setup messages; WebRTC still generates and holds the media keys. */
internal class CallIdentity(context: Context, private val alias: String = "saoreo-call-identity-v1") {
    private val trust = context.getSharedPreferences("call-verified-devices-v1", Context.MODE_PRIVATE)
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val pair by lazy {
        synchronized(CallIdentity::class.java) {
            val store = keyStore()
            val entry = store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            if (entry != null) KeyPair(entry.certificate.publicKey, entry.privateKey)
            else KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
                generateKeyPair()
            }
        }
    }
    private val trustKey = "$alias:paired-partner"
    private val pairingAlias = "$alias:pairing-key"

    fun exchange(room: String, me: String, peer: String, roomSecret: String? = null): CallAuthentication? {
        val key = if (roomSecret == null) keyStore().getKey(pairingAlias, null) as? SecretKey
            else callPairingKey(roomSecret)
        return key?.let { CallAuthentication(pair, room, me, peer, it) }
    }

    fun startPairing(secret: String): Boolean = synchronized(CallIdentity::class.java) {
        keyStore().setEntry(pairingAlias, KeyStore.SecretKeyEntry(callPairingKey(secret)),
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).setDigests(KeyProperties.DIGEST_SHA256).build())
        trust.edit().remove(trustKey).commit()
    }
    fun pairedFingerprint(): String? = trust.getString(trustKey, null)
    fun rememberPaired(exchange: CallAuthentication): Boolean = synchronized(CallIdentity::class.java) {
        val existing = pairedFingerprint()
        existing == exchange.remoteFingerprint || existing == null &&
            trust.edit().putString(trustKey, exchange.remoteFingerprint).commit()
    }
    fun forgetAll() = synchronized(CallIdentity::class.java) {
        keyStore().deleteEntry(pairingAlias)
        trust.edit().remove(trustKey).apply()
    }
}

internal class CallAuthentication(private val keys: KeyPair, private val room: String,
    private val me: String, private val peer: String, private val pairingKey: SecretKey? = null,
) {
    private val localKey = encode(keys.public.encoded)
    private val nonce = encode(ByteArray(32).also(SecureRandom()::nextBytes))
    private data class Participant(val key: PublicKey, val encoded: String, val nonce: String)
    private var remote: Participant? = null
    private var localVerified = false
    private var remoteVerified = false
    private var signalSequence = 0L
    private var lastAccepted = 0L
    val ready get() = localVerified && remoteVerified
    val remoteFingerprint get() = remote?.let { encode(hash(it.key.encoded)) } ?: error("Missing call identity")

    val hello: JSONObject by lazy {
        val body = message("hello", room, me, peer, localKey, nonce)
        JSONObject().put("type", "authHello").put("key", localKey).put("nonce", nonce)
            .put("proof", sign(body)).apply { pairingTag(body)?.let { put("pairProof", encode(it)) } }
    }

    fun receiveHello(data: JSONObject): Boolean = runCatching {
        val encoded = data.getString("key")
        val challenge = data.getString("nonce")
        if (encoded.length > 160 || challenge.length != 43 || encoded == localKey || challenge == nonce ||
            decode(challenge).size != 32) return false
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(encoded)))
        if (encode(key.encoded) != encoded || (key as? java.security.interfaces.ECPublicKey)?.params?.order?.bitLength() != 256)
            return false
        val body = message("hello", room, peer, me, encoded, challenge)
        if (!verify(key, data.optString("proof"), body)) return false
        val expectedTag = pairingTag(body)
        if (expectedTag != null) {
            val tag = data.optString("pairProof")
            if (tag.length != 43 || !MessageDigest.isEqual(expectedTag, decode(tag))) return false
        }
        val known = remote
        if (known != null) return known.encoded == encoded && known.nonce == challenge
        remote = Participant(key, encoded, challenge)
        true
    }.getOrDefault(false)

    fun confirm() { check(remote != null); localVerified = true }

    fun approval(): JSONObject? {
        val partner = remote ?: return null
        if (!localVerified) return null
        return JSONObject().put("type", "authReady")
            .put("proof", sign(message("ready", room, me, peer, nonce, partner.nonce, localKey, partner.encoded)))
    }

    fun receiveApproval(data: JSONObject): Boolean {
        val partner = remote ?: return false
        if (!verify(partner.key, data.optString("proof"),
                message("ready", room, peer, me, partner.nonce, nonce, partner.encoded, localKey))) return false
        remoteVerified = true
        return true
    }

    fun seal(payload: JSONObject): JSONObject {
        val partner = checkNotNull(remote)
        check(ready)
        val sequence = ++signalSequence
        val body = payload.toString()
        return JSONObject().put("type", "authenticatedSignal").put("seq", sequence).put("body", body)
            .put("proof", sign(message("signal", room, me, peer, nonce, partner.nonce, sequence.toString(), body)))
    }

    fun open(data: JSONObject): JSONObject? {
        val partner = remote ?: return null
        if (!ready) return null
        val seq = data.optLong("seq", 0)
        val body = data.optString("body")
        if (seq <= lastAccepted || body.length !in 1..20000 || !verify(partner.key, data.optString("proof"),
                message("signal", room, peer, me, partner.nonce, nonce, seq.toString(), body))) return null
        val payload = runCatching { JSONObject(body) }.getOrNull() ?: return null
        lastAccepted = seq
        return payload
    }

    private fun sign(data: ByteArray): String = encode(Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private)
        update(data)
        sign()
    })

    private fun pairingTag(body: ByteArray): ByteArray? = pairingKey?.let { key ->
        Mac.getInstance("HmacSHA256").run { init(key); doFinal(body) }
    }

    private fun verify(key: PublicKey, proof: String, data: ByteArray): Boolean {
        if (proof.length !in 1..108) return false
        return runCatching {
            Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(data); verify(decode(proof)) }
        }.getOrDefault(false)
    }
}

internal fun newCallPairingSecret(): String = encode(ByteArray(32).also(SecureRandom()::nextBytes))
internal fun callPairingKey(secret: String): SecretKey = SecretKeySpec(decode(secret), "HmacSHA256")

private fun message(purpose: String, vararg fields: String): ByteArray = ByteArrayOutputStream().apply {
    DataOutputStream(this).use { output ->
        for (field in listOf("SAOREO call authentication v1", purpose, *fields)) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            output.writeInt(bytes.size)
            output.write(bytes)
        }
    }
}.toByteArray()

private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
private fun decode(value: String) = Base64.getUrlDecoder().decode(value)
