package me.rapierxbox.shellyelevatev2.api;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.math.BigInteger;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Calendar;
import java.util.Date;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.security.auth.x500.X500Principal;

// the tls key of the v1 api. an ec p-256 key that never leaves the android keystore
// the keystore signs its own certificate and clients pin the sha-256 of that certificate at pairing
// the key survives app updates and only a reinstall creates a new one which means pairing again
public final class TlsIdentity {
    private static final String TAG = "TlsIdentity";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "shellyelevate-api-v1";

    private static volatile TlsIdentity instance;

    private final PrivateKey privateKey;
    private final X509Certificate certificate;
    private final byte[] certificateSha256;
    private final SSLServerSocketFactory serverSocketFactory;

    private TlsIdentity(PrivateKey privateKey, X509Certificate certificate) throws Exception {
        this.privateKey = privateKey;
        this.certificate = certificate;
        this.certificateSha256 = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[]{new SingleKeyManager()}, null, new SecureRandom());
        this.serverSocketFactory = context.getServerSocketFactory();
    }

    // loads or creates the key. slow the first time so call it off the main thread
    public static TlsIdentity get() throws Exception {
        TlsIdentity current = instance;
        if (current != null) return current;
        synchronized (TlsIdentity.class) {
            if (instance == null) instance = load();
            return instance;
        }
    }

    // null until get succeeded once
    public static TlsIdentity peek() {
        return instance;
    }

    private static TlsIdentity load() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (!keyStore.containsAlias(ALIAS)) generate();
        PrivateKey key = (PrivateKey) keyStore.getKey(ALIAS, null);
        Certificate cert = keyStore.getCertificate(ALIAS);
        if (key == null || !(cert instanceof X509Certificate)) {
            // a half written entry from an interrupted first start
            Log.w(TAG, "Keystore entry incomplete, creating a new key");
            keyStore.deleteEntry(ALIAS);
            generate();
            key = (PrivateKey) keyStore.getKey(ALIAS, null);
            cert = keyStore.getCertificate(ALIAS);
        }
        TlsIdentity identity = new TlsIdentity(key, (X509Certificate) cert);
        Log.i(TAG, "TLS certificate " + identity.fingerprint());
        return identity;
    }

    private static void generate() throws Exception {
        Calendar notAfter = Calendar.getInstance();
        notAfter.add(Calendar.YEAR, 30);
        Calendar notBefore = Calendar.getInstance();
        // displays without rtc boot with a clock in the past so the certificate must already be valid then
        notBefore.set(2020, Calendar.JANUARY, 1);
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
        generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                // none lets the tls stack sign its own digest
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(new X500Principal("CN=ShellyElevate"))
                .setCertificateSerialNumber(new BigInteger(64, new SecureRandom()).abs().add(BigInteger.ONE))
                .setCertificateNotBefore(notBefore.getTime())
                .setCertificateNotAfter(notAfter.getTime())
                .build());
        generator.generateKeyPair();
        Log.i(TAG, "Created the TLS key");
    }

    // tls 1.2 and newer. without a list nanohttpd would enable every protocol the device knows
    public String[] protocols() {
        java.util.List<String> wanted = new java.util.ArrayList<>();
        try {
            for (String protocol : SSLContext.getDefault().getSupportedSSLParameters().getProtocols()) {
                if (protocol.equals("TLSv1.2") || protocol.equals("TLSv1.3")) wanted.add(protocol);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read the tls protocols", e);
        }
        return wanted.isEmpty() ? new String[]{"TLSv1.2"} : wanted.toArray(new String[0]);
    }

    public SSLServerSocketFactory serverSocketFactory() {
        return serverSocketFactory;
    }

    // raw sha-256 of the der certificate as used by the pairing proof
    public byte[] certificateSha256() {
        return certificateSha256.clone();
    }

    // lowercase hex sha-256 of the der certificate
    public String fingerprint() {
        return Hex.encode(certificateSha256);
    }

    public Date notAfter() {
        return certificate.getNotAfter();
    }

    public byte[] certificateDer() throws CertificateEncodingException {
        return certificate.getEncoded();
    }

    // always answers with the one keystore key
    private final class SingleKeyManager extends X509ExtendedKeyManager {
        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return keyType == null || keyType.startsWith("EC") ? ALIAS : null;
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return chooseServerAlias(keyType, issuers, null);
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return new String[]{ALIAS};
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return ALIAS.equals(alias) ? new X509Certificate[]{certificate} : null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return ALIAS.equals(alias) ? privateKey : null;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return null;
        }
    }
}
