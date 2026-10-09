package me.rapierxbox.shellyelevatev2.helper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okio.Buffer;
import okio.ByteString;

// the system store first and then the bundled roots the download hosts chain to
// so an android 7 firmware without isrg root x1 or usertrust still validates certificates
public final class BundledTrustManager implements X509TrustManager {
    static final String ROOTS_RESOURCE = "download_roots.pem";

    private final X509TrustManager system;
    private final X509TrustManager bundled;

    BundledTrustManager(X509TrustManager system, X509TrustManager bundled) {
        this.system = system;
        this.bundled = bundled;
    }

    public static BundledTrustManager create() throws GeneralSecurityException, IOException {
        return new BundledTrustManager(trustManagerFor(null), trustManagerFor(bundledRootStore()));
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        system.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        try {
            system.checkServerTrusted(chain, authType);
        } catch (CertificateException e) {
            try {
                bundled.checkServerTrusted(chain, authType);
            } catch (CertificateException bundledFailure) {
                e.addSuppressed(bundledFailure);
                throw e;
            }
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        X509Certificate[] a = system.getAcceptedIssuers();
        X509Certificate[] b = bundled.getAcceptedIssuers();
        X509Certificate[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    static X509TrustManager trustManagerFor(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new GeneralSecurityException("no x509 trust manager");
    }

    static KeyStore bundledRootStore() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        List<X509Certificate> roots = bundledRoots();
        for (int i = 0; i < roots.size(); i++) store.setCertificateEntry("root" + i, roots.get(i));
        return store;
    }

    static List<X509Certificate> bundledRoots() throws GeneralSecurityException, IOException {
        try (InputStream in = BundledTrustManager.class.getResourceAsStream(ROOTS_RESOURCE)) {
            if (in == null) throw new IOException(ROOTS_RESOURCE + " missing");
            return parsePem(new Buffer().readFrom(in).readString(StandardCharsets.US_ASCII));
        }
    }

    // pem parsed by hand since the platform factories differ in what text they skip
    static List<X509Certificate> parsePem(String pem) throws GeneralSecurityException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> certs = new ArrayList<>();
        String begin = "-----BEGIN CERTIFICATE-----";
        String end = "-----END CERTIFICATE-----";
        int from = 0;
        while (true) {
            int start = pem.indexOf(begin, from);
            if (start < 0) break;
            int stop = pem.indexOf(end, start);
            if (stop < 0) throw new CertificateException("unterminated certificate");
            ByteString der = ByteString.decodeBase64(pem.substring(start + begin.length(), stop).replaceAll("\\s", ""));
            if (der == null) throw new CertificateException("bad base64");
            certs.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der.toByteArray())));
            from = stop + end.length();
        }
        if (certs.isEmpty()) throw new CertificateException("no certificates");
        return certs;
    }
}
