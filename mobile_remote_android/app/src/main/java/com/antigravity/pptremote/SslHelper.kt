package com.antigravity.pptremote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Calendar
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

object SslHelper {
    private const val ALIAS = "ppt_remote_web_server"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    fun getSSLContext(context: Context): SSLContext {
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

            if (!keyStore.containsAlias(ALIAS)) {
                Log.i("SslHelper", "Generating self-signed certificate in AndroidKeyStore...")
                val kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA,
                    KEYSTORE_PROVIDER
                )
                val start = Calendar.getInstance()
                val end = Calendar.getInstance().apply { add(Calendar.YEAR, 10) }

                kpg.initialize(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                    .setCertificateSubject(X500Principal("CN=PPT Remote Web Server"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setStartDate(start.time)
                    .setEndDate(end.time)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build()
                )
                kpg.generateKeyPair()
                Log.i("SslHelper", "Self-signed certificate generated successfully.")
            }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, null)
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, null, null)
            }
            return sslContext
        } catch (e: Exception) {
            Log.e("SslHelper", "Failed to initialize SSLContext from AndroidKeyStore", e)
            throw e
        }
    }
}
