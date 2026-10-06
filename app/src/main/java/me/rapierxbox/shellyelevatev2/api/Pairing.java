package me.rapierxbox.shellyelevatev2.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// on screen code pairing from protocol-v1 section 3b. one pending pairing at a time
// the code never crosses the network. the client proves it with an hmac bound to our certificate
public final class Pairing {
    public static final long EXPIRY_MS = 120_000;
    public static final int MAX_ATTEMPTS = 5;
    // pair starts per window before 429 so a lan host cannot keep the code dialog up forever
    static final int MAX_STARTS = 10;
    static final long START_WINDOW_MS = 10 * 60_000;
    private static final byte[] CONTEXT = "shellyelevate-pair-v1".getBytes(StandardCharsets.US_ASCII);

    public enum Outcome { OK, INVALID, EXPIRED }

    public static final class Pending {
        public final String id;
        public final String code;
        public final String clientId;
        public final String clientName;
        public final long expiresAt;
        int attempts;

        Pending(String id, String code, String clientId, String clientName, long expiresAt) {
            this.id = id;
            this.code = code;
            this.clientId = clientId;
            this.clientName = clientName;
            this.expiresAt = expiresAt;
        }
    }

    public static final class RateLimited extends Exception {
        RateLimited() {
            super("too many pairing requests");
        }
    }

    // the time source so tests can move the clock
    interface Clock {
        long now();
    }

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Deque<Long> starts = new ArrayDeque<>();
    private Pending pending;

    public Pairing() {
        this(System::currentTimeMillis);
    }

    Pairing(Clock clock) {
        this.clock = clock;
    }

    // starts a pairing and replaces any pending one
    public synchronized Pending start(String clientId, String clientName) throws RateLimited {
        long now = clock.now();
        while (!starts.isEmpty() && now - starts.peekFirst() > START_WINDOW_MS) starts.removeFirst();
        if (starts.size() >= MAX_STARTS) throw new RateLimited();
        starts.addLast(now);
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        pending = new Pending(UUID.randomUUID().toString(), code, clientId, clientName, now + EXPIRY_MS);
        return pending;
    }

    // the pending pairing or null when none is open
    public synchronized Pending current() {
        if (pending != null && clock.now() > pending.expiresAt) pending = null;
        return pending;
    }

    public synchronized void cancel(String pairingId) {
        if (pending != null && (pairingId == null || pending.id.equals(pairingId))) pending = null;
    }

    // checks a proof. OK consumes the pairing and returns the pending entry through out[0]
    public synchronized Outcome confirm(String pairingId, String proofHex, byte[] certificateSha256, Pending[] out) {
        Pending p = pending;
        if (p == null || !p.id.equals(pairingId)) return Outcome.EXPIRED;
        if (clock.now() > p.expiresAt) {
            pending = null;
            return Outcome.EXPIRED;
        }
        byte[] presented = Hex.decode(proofHex == null ? "" : proofHex.trim().toLowerCase(Locale.ROOT));
        byte[] expected = proof(p.code, p.id, certificateSha256);
        if (presented != null && MessageDigest.isEqual(presented, expected)) {
            pending = null;
            if (out != null && out.length > 0) out[0] = p;
            return Outcome.OK;
        }
        if (++p.attempts >= MAX_ATTEMPTS) pending = null;
        return Outcome.INVALID;
    }

    // hmac-sha256 keyed with the code over context 0x00 pairing_id 0x00 sha-256(cert der)
    static byte[] proof(String code, String pairingId, byte[] certificateSha256) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(code.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            mac.update(CONTEXT);
            mac.update((byte) 0);
            mac.update(pairingId.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(certificateSha256);
            return mac.doFinal();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
