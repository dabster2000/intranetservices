package dk.trustworks.intranet.recruitmentservice.security;

import dk.trustworks.intranet.recruitmentservice.security.PublicApplyRateLimitFilter.SlidingWindowLimiter;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static dk.trustworks.intranet.recruitmentservice.security.PublicApplyRateLimitFilter.resolveBucketKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public-apply rate limiter, driven with explicit inputs and
 * instants — deliberately no wall-clock waits (no flaky timing tests).
 * Two halves:
 * <ul>
 *   <li>{@code resolveBucketKey} — the identity-aware bucketing rules:
 *       anonymous callers bucket on rightmost XFF and can NEVER use
 *       {@code X-Client-IP}; authenticated (BFF) callers bucket on
 *       {@code X-Client-IP}, or skip limiting entirely when the header
 *       is absent;</li>
 *   <li>{@code SlidingWindowLimiter} — the 10/60 s window itself.</li>
 * </ul>
 */
class PublicApplyRateLimitFilterTest {

    private static final int MAX = 10;
    private static final Instant T0 = Instant.parse("2026-07-22T12:00:00Z");

    // ---- Identity-aware bucket keys -------------------------------------------

    @Test
    void anonymous_bucketsOnRightmostForwardedFor() {
        assertEquals("xff:5.6.7.8",
                resolveBucketKey(true, null, "1.2.3.4, 5.6.7.8", "unknown"));
        assertEquals("xff:1.2.3.4",
                resolveBucketKey(true, null, "1.2.3.4", "unknown"));
    }

    @Test
    void anonymous_spoofedClientIpHeader_isIgnored() {
        String withSpoof = resolveBucketKey(true, "10.99.99.99", "1.2.3.4, 5.6.7.8", "unknown");
        String withoutSpoof = resolveBucketKey(true, null, "1.2.3.4, 5.6.7.8", "unknown");
        assertEquals(withoutSpoof, withSpoof,
                "X-Client-IP from an anonymous caller must never influence the bucket");
        assertEquals("xff:5.6.7.8", withSpoof);
    }

    @Test
    void anonymous_withoutForwardedFor_fallsBackToRemoteAddress() {
        assertEquals("xff:192.168.1.7", resolveBucketKey(true, null, null, "192.168.1.7"));
        assertEquals("xff:192.168.1.7", resolveBucketKey(true, null, "  ", "192.168.1.7"));
    }

    @Test
    void authenticated_withClientIp_bucketsPerClient_notShared() {
        String clientA = resolveBucketKey(false, "203.0.113.10", "10.0.0.5", "10.0.0.5");
        String clientB = resolveBucketKey(false, "203.0.113.11", "10.0.0.5", "10.0.0.5");
        assertEquals("client:203.0.113.10", clientA);
        assertEquals("client:203.0.113.11", clientB);
        assertNotEquals(clientA, clientB,
                "two applicants behind the same BFF task must not share a bucket");

        // And the window honors the separation.
        SlidingWindowLimiter limiter = new SlidingWindowLimiter();
        for (int i = 0; i < MAX; i++) {
            assertTrue(limiter.tryAcquire(clientA, T0, MAX).allowed());
        }
        assertFalse(limiter.tryAcquire(clientA, T0, MAX).allowed());
        assertTrue(limiter.tryAcquire(clientB, T0, MAX).allowed(),
                "a saturated applicant must not throttle a different applicant");
    }

    @Test
    void authenticated_withoutClientIp_skipsRateLimiting() {
        assertNull(resolveBucketKey(false, null, "10.0.0.5", "10.0.0.5"),
                "trusted internal caller without client attribution is not limited");
        assertNull(resolveBucketKey(false, "  ", "10.0.0.5", "10.0.0.5"));
    }

    @Test
    void anonymousFlood_cannotExhaustAnAuthenticatedApplicantsBucket() {
        // Same IP string via both branches → prefixed keys keep them apart.
        String anonymousKey = resolveBucketKey(true, null, "203.0.113.10", "unknown");
        String authenticatedKey = resolveBucketKey(false, "203.0.113.10", "10.0.0.5", "10.0.0.5");
        assertNotEquals(anonymousKey, authenticatedKey);

        SlidingWindowLimiter limiter = new SlidingWindowLimiter();
        for (int i = 0; i < MAX; i++) {
            limiter.tryAcquire(anonymousKey, T0, MAX);
        }
        assertFalse(limiter.tryAcquire(anonymousKey, T0, MAX).allowed());
        assertTrue(limiter.tryAcquire(authenticatedKey, T0, MAX).allowed(),
                "an anonymous flood spoofing the victim's IP must not exhaust the BFF-mediated bucket");
    }

    // ---- Sliding window --------------------------------------------------------

    @Test
    void allowsUpToMax_thenRejects() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter();
        for (int i = 0; i < MAX; i++) {
            assertTrue(limiter.tryAcquire("ip-1", T0.plusSeconds(i), MAX).allowed(),
                    "request " + (i + 1) + " within the limit must pass");
        }
        assertFalse(limiter.tryAcquire("ip-1", T0.plusSeconds(MAX), MAX).allowed(),
                "request " + (MAX + 1) + " within the window must be rejected");
    }

    @Test
    void windowSlides_oldRequestsExpire() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter();
        for (int i = 0; i < MAX; i++) {
            assertTrue(limiter.tryAcquire("ip-1", T0, MAX).allowed());
        }
        assertFalse(limiter.tryAcquire("ip-1", T0.plusSeconds(30), MAX).allowed(),
                "still inside the 60s window");
        assertTrue(limiter.tryAcquire("ip-1", T0.plusSeconds(61), MAX).allowed(),
                "the burst at T0 has left the window");
    }

    @Test
    void retryAfter_pointsAtTheOldestRequestExpiry() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter();
        for (int i = 0; i < MAX; i++) {
            limiter.tryAcquire("ip-1", T0, MAX);
        }
        SlidingWindowLimiter.Decision rejected =
                limiter.tryAcquire("ip-1", T0.plusSeconds(20), MAX);
        assertFalse(rejected.allowed());
        assertEquals(40, rejected.retryAfterSeconds(),
                "oldest request at T0 expires at T0+60 → 40s from T0+20");
    }

    @Test
    void retryAfter_neverBelowOneSecond() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter();
        for (int i = 0; i < MAX; i++) {
            limiter.tryAcquire("ip-1", T0, MAX);
        }
        SlidingWindowLimiter.Decision rejected =
                limiter.tryAcquire("ip-1", T0.plusSeconds(59).plusMillis(900), MAX);
        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfterSeconds() >= 1);
    }

    @Test
    void ipsAreIsolated() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter();
        for (int i = 0; i < MAX; i++) {
            limiter.tryAcquire("ip-1", T0, MAX);
        }
        assertFalse(limiter.tryAcquire("ip-1", T0, MAX).allowed());
        assertTrue(limiter.tryAcquire("ip-2", T0, MAX).allowed(),
                "one saturated IP must not throttle another");
    }

    @Test
    void rejectionsDoNotConsumeWindowSlots() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter();
        for (int i = 0; i < MAX; i++) {
            limiter.tryAcquire("ip-1", T0, MAX);
        }
        for (int i = 0; i < 5; i++) {
            assertFalse(limiter.tryAcquire("ip-1", T0.plusSeconds(10), MAX).allowed());
        }
        assertTrue(limiter.tryAcquire("ip-1", T0.plusSeconds(61), MAX).allowed(),
                "hammering while rejected must not extend the lockout");
    }
}
