package dk.trustworks.intranet.recruitmentservice.events;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 generator (RFC 9562): 48-bit Unix-epoch millisecond timestamp,
 * 4-bit version (7), 12 + 62 random bits. Time-ordered, so event ids sort
 * roughly by creation time — the property the spec (§3.3) wants for
 * {@code recruitment_events.event_id}.
 * <p>
 * Java 21 has no built-in v7 factory ({@link UUID#randomUUID()} is v4),
 * hence this small local implementation. Uniqueness within a millisecond
 * comes from 74 random bits; strict monotonicity is NOT guaranteed and NOT
 * required — the global order key is {@code seq}, not {@code event_id}.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        return generate(System.currentTimeMillis());
    }

    /** Visible for tests: build a v7 UUID for a specific timestamp. */
    static UUID generate(long unixMillis) {
        byte[] rnd = new byte[10];
        RANDOM.nextBytes(rnd);

        // MSB: 48-bit timestamp | 4-bit version (0111) | 12 random bits
        long msb = (unixMillis & 0xFFFFFFFFFFFFL) << 16;
        msb |= 0x7000L;
        msb |= ((rnd[0] & 0x0FL) << 8) | (rnd[1] & 0xFFL);

        // LSB: 2-bit variant (10) | 62 random bits
        long lsb = 0L;
        for (int i = 2; i < 10; i++) {
            lsb = (lsb << 8) | (rnd[i] & 0xFFL);
        }
        lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;

        return new UUID(msb, lsb);
    }
}
