package dk.trustworks.intranet.recruitmentservice.events;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UuidV7Test {

    @Test
    void generated_isVersion7_variant2() {
        UUID uuid = UuidV7.generate();
        assertEquals(7, uuid.version());
        assertEquals(2, uuid.variant());
    }

    @Test
    void timestampBits_roundTrip() {
        long millis = 1_753_180_000_123L; // fixed instant
        UUID uuid = UuidV7.generate(millis);
        long extracted = uuid.getMostSignificantBits() >>> 16;
        assertEquals(millis, extracted, "top 48 bits must be the unix-epoch milliseconds");
    }

    @Test
    void laterTimestamps_sortLater_asStrings() {
        UUID earlier = UuidV7.generate(1_000_000_000_000L);
        UUID later = UuidV7.generate(1_000_000_000_001L);
        assertTrue(earlier.toString().compareTo(later.toString()) < 0,
                "v7 string ordering must follow the timestamp");
    }

    @Test
    void tightLoop_producesDistinctIds() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(seen.add(UuidV7.generate()), "duplicate UUID generated");
        }
    }

    @Test
    void stringForm_fitsTheVarchar36Column() {
        assertEquals(36, UuidV7.generate().toString().length());
    }
}
