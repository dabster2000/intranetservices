package dk.trustworks.intranet.documentservice.migration;

import dk.trustworks.intranet.documentservice.migration.util.QuickXorHash;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * QuickXorHash port sanity (runbook 2a-4). The authoritative
 * compatibility check against Graph's real values happens at the staging
 * rehearsal (2a-8) — a copy-time mismatch fails the item loudly, so a
 * broken port cannot silently corrupt anything.
 */
class QuickXorHashTest {

    @Test
    void emptyInputHashesToLengthXorOnly() {
        // No data bits set; length 0 XORs nothing → 20 zero bytes.
        assertEquals(Base64.getEncoder().encodeToString(new byte[20]), QuickXorHash.base64(new byte[0]));
    }

    @Test
    void chunkedUpdatesEqualSingleShot() {
        for (int size : new int[]{1, 7, 159, 160, 161, 1024, 5000}) {
            byte[] data = deterministicBytes(size);
            String singleShot = QuickXorHash.base64(data);

            for (int chunk : new int[]{1, 3, 64, 160, 333}) {
                QuickXorHash chunked = new QuickXorHash();
                int offset = 0;
                while (offset < size) {
                    int len = Math.min(chunk, size - offset);
                    chunked.update(data, offset, len);
                    offset += len;
                }
                assertEquals(singleShot, chunked.digestBase64(),
                        "size=" + size + " chunk=" + chunk);
            }
        }
    }

    @Test
    void contentChangesTheHash() {
        byte[] a = deterministicBytes(500);
        byte[] b = a.clone();
        b[250] ^= 0x01;
        assertNotEquals(QuickXorHash.base64(a), QuickXorHash.base64(b));
    }

    @Test
    void lengthChangesTheHash() {
        byte[] longer = deterministicBytes(300);
        byte[] prefix = new byte[299];
        System.arraycopy(longer, 0, prefix, 0, 299);
        assertNotEquals(QuickXorHash.base64(longer), QuickXorHash.base64(prefix));
    }

    @Test
    void digestIsTwentyBytesBase64() {
        String digest = QuickXorHash.base64(deterministicBytes(42));
        assertEquals(20, Base64.getDecoder().decode(digest).length);
    }

    private static byte[] deterministicBytes(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }
}
