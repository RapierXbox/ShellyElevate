package me.rapierxbox.shellyelevatev2.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class PairingTest {
    private long now = 1_000_000;
    private final Pairing pairing = new Pairing(() -> now);

    private static byte[] certSha() throws Exception {
        return MessageDigest.getInstance("SHA-256").digest("cert".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void proofMatchesTheIntegrationReference() throws Exception {
        // pairing_proof("012345", "abc123", sha256(b"cert").hexdigest()) from api/client.py
        assertEquals("31c8a56f17a014f0a3c52ed7b3c6b10db71d0b94b482e25f0579de24aa432fe9",
                Hex.encode(Pairing.proof("012345", "abc123", certSha())));
    }

    @Test
    public void correctProofPairsOnce() throws Exception {
        Pairing.Pending p = pairing.start("client", "Home Assistant");
        String proof = Hex.encode(Pairing.proof(p.code, p.id, certSha()));
        Pairing.Pending[] out = new Pairing.Pending[1];
        assertEquals(Pairing.Outcome.OK, pairing.confirm(p.id, proof, certSha(), out));
        assertSame(p, out[0]);
        assertEquals(Pairing.Outcome.EXPIRED, pairing.confirm(p.id, proof, certSha(), null));
    }

    @Test
    public void proofForAnotherCertificateIsRejected() throws Exception {
        Pairing.Pending p = pairing.start("client", "Home Assistant");
        byte[] other = MessageDigest.getInstance("SHA-256").digest(new byte[]{1});
        String proof = Hex.encode(Pairing.proof(p.code, p.id, other));
        assertEquals(Pairing.Outcome.INVALID, pairing.confirm(p.id, proof, certSha(), null));
    }

    @Test
    public void fiveWrongProofsInvalidate() throws Exception {
        Pairing.Pending p = pairing.start("client", "Home Assistant");
        for (int i = 0; i < Pairing.MAX_ATTEMPTS; i++) {
            assertEquals(Pairing.Outcome.INVALID, pairing.confirm(p.id, "00", certSha(), null));
        }
        String proof = Hex.encode(Pairing.proof(p.code, p.id, certSha()));
        assertEquals(Pairing.Outcome.EXPIRED, pairing.confirm(p.id, proof, certSha(), null));
    }

    @Test
    public void expiresAfterTwoMinutes() throws Exception {
        Pairing.Pending p = pairing.start("client", "Home Assistant");
        now += Pairing.EXPIRY_MS + 1;
        assertNull(pairing.current());
        String proof = Hex.encode(Pairing.proof(p.code, p.id, certSha()));
        assertEquals(Pairing.Outcome.EXPIRED, pairing.confirm(p.id, proof, certSha(), null));
    }

    @Test
    public void newStartReplacesThePendingPairing() throws Exception {
        Pairing.Pending first = pairing.start("a", "A");
        Pairing.Pending second = pairing.start("b", "B");
        assertNotNull(pairing.current());
        assertEquals(second.id, pairing.current().id);
        String proof = Hex.encode(Pairing.proof(first.code, first.id, certSha()));
        assertEquals(Pairing.Outcome.EXPIRED, pairing.confirm(first.id, proof, certSha(), null));
    }

    @Test(expected = Pairing.RateLimited.class)
    public void startsAreRateLimited() throws Exception {
        for (int i = 0; i <= Pairing.MAX_STARTS; i++) pairing.start("c", "C");
    }

    @Test
    public void repeatedWrongProofsLockPairingOut() throws Exception {
        Pairing.Pending p = null;
        for (int i = 0; i < Pairing.MAX_FAILURES; i++) {
            if (i % Pairing.MAX_ATTEMPTS == 0) p = pairing.start("c", "C");
            assertEquals(Pairing.Outcome.INVALID, pairing.confirm(p.id, "00", certSha(), null));
        }
        try {
            pairing.start("c", "C");
            throw new AssertionError("expected the lockout");
        } catch (Pairing.RateLimited expected) {
            // locked
        }
        now += Pairing.FAILURE_WINDOW_MS + 1;
        assertNotNull(pairing.start("c", "C"));
    }

    @Test
    public void codeIsSixDigits() throws Exception {
        for (int i = 0; i < 5; i++) {
            String code = pairing.start("c", "C").code;
            assertEquals(6, code.length());
            assertEquals(code, code.replaceAll("[^0-9]", ""));
        }
    }
}
