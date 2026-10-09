package me.rapierxbox.shellyelevatev2.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import javax.net.ssl.X509TrustManager;

import okio.Buffer;

public class BundledTrustManagerTest {
    // the chains were captured on this day so their leaf certificates are valid then
    private static final long CAPTURED = utc(2026, Calendar.OCTOBER, 9);

    @Test
    public void bundledRootsParseAndAreSelfSignedRoots() throws Exception {
        List<X509Certificate> roots = BundledTrustManager.bundledRoots();
        assertEquals(4, roots.size());
        Set<String> names = new HashSet<>();
        for (X509Certificate root : roots) {
            assertEquals(root.getSubjectX500Principal(), root.getIssuerX500Principal());
            root.verify(root.getPublicKey());
            root.checkValidity(new java.util.Date(CAPTURED));
            names.add(root.getSubjectX500Principal().getName());
        }
        assertTrue(names.toString(), names.toString().contains("ISRG Root X1"));
        assertTrue(names.toString(), names.toString().contains("USERTrust ECC"));
        assertTrue(names.toString(), names.toString().contains("USERTrust RSA"));
    }

    // what android 7 without the modern roots sees. only the bundle anchors the path
    @Test
    public void downloadHostsChainToTheBundledRoots() throws Exception {
        for (String host : new String[]{"raw.githubusercontent.com", "api.github.com", "repo.shelly.cloud"}) {
            validate(host, BundledTrustManager.bundledRoots());
        }
    }

    @Test
    public void unrelatedRootsDoNotValidate() throws Exception {
        List<X509Certificate> isrgOnly = new ArrayList<>();
        for (X509Certificate root : BundledTrustManager.bundledRoots()) {
            if (root.getSubjectX500Principal().getName().contains("ISRG")) isrgOnly.add(root);
        }
        try {
            validate("api.github.com", isrgOnly);
            fail("a sectigo chain must not validate against lets encrypt roots");
        } catch (CertPathValidatorException expected) {
            // no anchor for the chain
        }
    }

    @Test
    public void systemFailureFallsBackToTheBundle() throws Exception {
        X509Certificate[] chain = {BundledTrustManager.bundledRoots().get(0)};
        BundledTrustManager tm = new BundledTrustManager(rejecting(), accepting());
        tm.checkServerTrusted(chain, "RSA");
        BundledTrustManager both = new BundledTrustManager(rejecting(), rejecting());
        try {
            both.checkServerTrusted(chain, "RSA");
            fail("rejected by both must throw");
        } catch (CertificateException expected) {
            assertEquals(1, expected.getSuppressed().length);
        }
    }

    @Test
    public void parsePemSkipsCommentsAndRejectsEmptyInput() throws Exception {
        String pem = "# comment\n" + readResource("download_roots.pem");
        assertEquals(4, BundledTrustManager.parsePem(pem).size());
        try {
            BundledTrustManager.parsePem("# nothing here");
            fail("no certificate must throw");
        } catch (CertificateException expected) {
            // empty
        }
    }

    private static void validate(String host, List<X509Certificate> anchors) throws Exception {
        List<X509Certificate> chain = BundledTrustManager.parsePem(readResource("chain_" + host + ".pem"));
        // a self signed root a server sends along is never part of the path
        List<X509Certificate> path = new ArrayList<>();
        for (X509Certificate c : chain) {
            if (!c.getSubjectX500Principal().equals(c.getIssuerX500Principal())) path.add(c);
        }
        Set<TrustAnchor> trust = new HashSet<>();
        for (X509Certificate root : anchors) trust.add(new TrustAnchor(root, null));
        PKIXParameters params = new PKIXParameters(trust);
        params.setRevocationEnabled(false);
        params.setDate(new java.util.Date(CAPTURED));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        CertPathValidator.getInstance("PKIX").validate(cf.generateCertPath(path), params);
    }

    private static String readResource(String name) throws IOException {
        try (InputStream in = BundledTrustManagerTest.class.getResourceAsStream(name)) {
            assertNotNull(name, in);
            return new Buffer().readFrom(in).readString(StandardCharsets.US_ASCII);
        }
    }

    private static long utc(int year, int month, int day) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(year, month, day, 12, 0);
        return c.getTimeInMillis();
    }

    private static X509TrustManager rejecting() {
        return new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("no");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("no");
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private static X509TrustManager accepting() {
        return new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }
}
