package dk.trustworks.intranet.aggregates.invoice.services;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Backfill idempotency against a real schema (Decision D5). The backfill stamps the
 * settlement-group key and writes one {@code internal_invoice_phantom_link} row onto
 * every already-issued phantom-linked internal, then is safe to re-run.
 *
 * <p>This test asserts the <b>idempotency invariant directly</b> rather than seeding a
 * fixture, which makes it deterministic whether the DB has zero or many candidates: a
 * second run must stamp nothing new ({@code STAMPED == 0}), report every processable
 * internal as {@code ALREADY_DONE}, and add no new link rows. (A seeded
 * "{@code STAMPED >= 1}" assertion would be fragile — it fails the moment the backfill
 * has already run against this DB, which is exactly the state after the phase plan's
 * staging run.)
 *
 * <p>DB-gated: boots only where a MariaDB is present (CI/staging); locally the compile is
 * the gate (it catches signature/contract drift in {@link PhantomSettlementService}). The
 * positive "stamps a mapped internal, key matches its phantom" behaviour is verified by
 * the staging probe in the phase plan (Task 4) against real data.
 */
@QuarkusTest
@TestProfile(PhantomSettlementBackfillTest.NoDevServicesProfile.class)
class PhantomSettlementBackfillTest {

    public static class NoDevServicesProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.s3.devservices.enabled", "false",
                    "cvtool.username", "test-placeholder",
                    "cvtool.password", "test-placeholder"
            );
        }
    }

    @Inject
    PhantomSettlementService service;

    @Inject
    EntityManager em;

    @Test
    void backfill_isIdempotent_secondRunStampsNothingAndAddsNoLinks() {
        // First run: stamps any unstamped candidates + writes their link rows.
        Map<String, Integer> first = service.backfillExistingInternals();
        long linksAfterFirst = linkCount();

        // Second run: every processable internal is now ALREADY_DONE.
        Map<String, Integer> second = service.backfillExistingInternals();
        long linksAfterSecond = linkCount();

        int firstProcessed = first.values().stream().mapToInt(Integer::intValue).sum();
        int secondProcessed = second.values().stream().mapToInt(Integer::intValue).sum();

        assertEquals(firstProcessed, secondProcessed,
                "the candidate set is stable across runs");

        assertEquals(0,
                second.getOrDefault(PhantomSettlementService.BackfillOutcome.STAMPED.name(), 0),
                "re-run stamps nothing new (idempotent)");

        int doneInFirst = first.getOrDefault(PhantomSettlementService.BackfillOutcome.STAMPED.name(), 0)
                + first.getOrDefault(PhantomSettlementService.BackfillOutcome.ALREADY_DONE.name(), 0);
        assertEquals(doneInFirst,
                second.getOrDefault(PhantomSettlementService.BackfillOutcome.ALREADY_DONE.name(), 0),
                "everything stamped or already-done in run 1 is ALREADY_DONE in run 2");

        assertEquals(0,
                second.getOrDefault(PhantomSettlementService.BackfillOutcome.ERROR.name(), 0),
                "no internal errors on the idempotent re-run");

        assertEquals(linksAfterFirst, linksAfterSecond,
                "re-run writes no new internal_invoice_phantom_link rows");
    }

    private long linkCount() {
        Object n = em.createNativeQuery("SELECT COUNT(*) FROM internal_invoice_phantom_link").getSingleResult();
        return ((Number) n).longValue();
    }
}
