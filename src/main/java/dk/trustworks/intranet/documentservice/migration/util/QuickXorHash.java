package dk.trustworks.intranet.documentservice.migration.util;

import java.util.Base64;

/**
 * Java port of Microsoft's QuickXorHash — the checksum Graph exposes as
 * {@code file.hashes.quickXorHash} on OneDrive-for-Business / SharePoint
 * drive items. Used by the migration copier (runbook 2a-4 / spec §9.6)
 * to compare the bytes we actually downloaded against SharePoint's own
 * hash before anything is stored.
 *
 * <p>Faithful port of the reference C# implementation from the OneDrive
 * developer documentation, including its cell-rollover quirks — the
 * point is byte-for-byte compatibility with what Graph reports, not
 * elegance. 160-bit state over three 64-bit cells (the last cell holds
 * 32 bits); each input byte is XORed in at a position that advances 11
 * bits per byte; the total length is XORed into the last 8 bytes.</p>
 */
public final class QuickXorHash {

    private static final int WIDTH_IN_BITS = 160;
    private static final int SHIFT = 11;
    private static final int BITS_IN_LAST_CELL = 32;

    private final long[] data = new long[(WIDTH_IN_BITS - 1) / 64 + 1];
    private long lengthSoFar;
    private int shiftSoFar;

    /** Convenience: hash a full byte array and return the base64 digest. */
    public static String base64(byte[] bytes) {
        QuickXorHash hash = new QuickXorHash();
        hash.update(bytes, 0, bytes.length);
        return hash.digestBase64();
    }

    /** Feed bytes into the hash (may be called repeatedly). */
    public void update(byte[] array, int offset, int length) {
        int currentShift = shiftSoFar;
        int vectorArrayIndex = currentShift / 64;
        int vectorOffset = currentShift % 64;
        int iterations = Math.min(length, WIDTH_IN_BITS);

        for (int i = 0; i < iterations; i++) {
            boolean isLastCell = vectorArrayIndex == data.length - 1;
            int bitsInVectorCell = isLastCell ? BITS_IN_LAST_CELL : 64;

            if (vectorOffset <= bitsInVectorCell - 8) {
                for (int j = offset + i; j < length + offset; j += WIDTH_IN_BITS) {
                    data[vectorArrayIndex] ^= (long) (array[j] & 0xff) << vectorOffset;
                }
            } else {
                int index1 = vectorArrayIndex;
                int index2 = isLastCell ? 0 : vectorArrayIndex + 1;
                int low = bitsInVectorCell - vectorOffset;

                byte xoredByte = 0;
                for (int j = offset + i; j < length + offset; j += WIDTH_IN_BITS) {
                    xoredByte ^= array[j];
                }
                data[index1] ^= (long) (xoredByte & 0xff) << vectorOffset;
                data[index2] ^= (long) (xoredByte & 0xff) >>> low;
            }

            vectorOffset += SHIFT;
            // Reference implementation deliberately reuses the iteration's
            // isLastCell/bitsInVectorCell here (bug-compatible on purpose).
            while (vectorOffset >= bitsInVectorCell) {
                vectorArrayIndex = isLastCell ? 0 : vectorArrayIndex + 1;
                vectorOffset -= bitsInVectorCell;
            }
        }

        shiftSoFar = (shiftSoFar + SHIFT * (length % WIDTH_IN_BITS)) % WIDTH_IN_BITS;
        lengthSoFar += length;
    }

    /** Finish and return the 20-byte digest, base64-encoded like Graph reports it. */
    public String digestBase64() {
        byte[] rgb = new byte[(WIDTH_IN_BITS - 1) / 8 + 1];

        // Little-endian block copy of the full cells…
        for (int i = 0; i < data.length - 1; i++) {
            copyLittleEndian(data[i], rgb, i * 8, 8);
        }
        // …and the partial last cell.
        copyLittleEndian(data[data.length - 1], rgb, (data.length - 1) * 8,
                rgb.length - (data.length - 1) * 8);

        // XOR the total length into the last 8 bytes (little-endian).
        for (int i = 0; i < 8; i++) {
            rgb[WIDTH_IN_BITS / 8 - 8 + i] ^= (byte) (lengthSoFar >>> (8 * i));
        }
        return Base64.getEncoder().encodeToString(rgb);
    }

    private static void copyLittleEndian(long value, byte[] target, int offset, int count) {
        for (int i = 0; i < count; i++) {
            target[offset + i] = (byte) (value >>> (8 * i));
        }
    }
}
