package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ISAE 3000 draw must be deterministic (an auditor can recompute any
 * historical draw from the candidate uuid and the logged rate), boundary
 * rates must behave exactly, and the selection frequency must track the
 * configured rate.
 */
class RecordCheckServiceTest {

    @Test
    void draw_isDeterministicPerCandidate() {
        String candidate = "7d444840-9dc0-11d1-b245-5ffdce74fad2";
        boolean first = RecordCheckService.deterministicDraw(candidate, 20);
        for (int i = 0; i < 10; i++) {
            assertEquals(first, RecordCheckService.deterministicDraw(candidate, 20),
                    "same candidate + same rate must always draw the same result");
        }
    }

    @Test
    void boundaryRates_zeroNeverSelects_hundredAlwaysSelects() {
        for (int i = 0; i < 50; i++) {
            String candidate = UUID.randomUUID().toString();
            assertFalse(RecordCheckService.deterministicDraw(candidate, 0));
            assertTrue(RecordCheckService.deterministicDraw(candidate, 100));
        }
    }

    @Test
    void selectionFrequency_tracksTheRate() {
        int selected = 0;
        int samples = 10_000;
        for (int i = 0; i < samples; i++) {
            if (RecordCheckService.deterministicDraw(new UUID(0xACE0BA5EL + i, i).toString(), 20)) {
                selected++;
            }
        }
        double share = selected / (double) samples;
        assertTrue(share > 0.15 && share < 0.25,
                "a 20%% rate should select roughly one in five (got " + share + ")");
    }
}
