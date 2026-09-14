package dk.trustworks.intranet.apis.openai;

import dk.trustworks.intranet.apis.openai.OpenAIService.ProviderError;
import dk.trustworks.intranet.apis.openai.OpenAIService.SchemaAnswer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one failure {@link OpenAIService} reports as an exception rather than as an empty
 * body, and the line it is told apart by.
 *
 * <p>That line is the whole of this class. An exhausted credit balance and an ordinary rate
 * limit are both HTTP 429, they arrive through the same branch, and they want opposite
 * handling: a caller in a loop must stop for the first and carry on for the second. Reading
 * the status would collapse them, so the verdict is taken from {@code type}/{@code code} —
 * and a test that only proved "429 stops the run" would have proved the bug.
 *
 * <p>Bodies are the provider's real envelopes, including the one production answered on
 * 2026-09-14 when three CRM Slack channels were refused in a row.
 *
 * <p>Fast tier — no Quarkus boot, no network.
 */
class OpenAIQuotaTest {

    /** What production answered on 2026-09-14, message shortened. */
    private static final String OUT_OF_CREDIT = """
            {"error":{"message":"Your credit balance is too low to access the API.",
            "type":"insufficient_quota","param":null,"code":"credit_balance_exhausted"}}""";

    /** The other 429, which must NOT stop a run: it clears on its own. */
    private static final String RATE_LIMITED = """
            {"error":{"message":"Rate limit reached for gpt-5.6-terra",
            "type":"requests","param":null,"code":"rate_limit_exceeded"}}""";

    @Test
    void anExhaustedCreditBalanceIsRecognisedFromTheEnvelopeAndNotFromTheStatus() {
        ProviderError error = OpenAIService.providerError(429, OUT_OF_CREDIT);

        assertEquals("insufficient_quota", error.type());
        assertEquals("credit_balance_exhausted", error.code());
        assertTrue(error.isQuotaExhausted(), "the account cannot pay — no retry clears this");
    }

    @Test
    void anOrdinaryRateLimitIsNotAQuotaFailure() {
        ProviderError error = OpenAIService.providerError(429, RATE_LIMITED);

        assertFalse(error.isQuotaExhausted(),
                "same status, opposite handling: a rate limit is transient and the caller should carry on");
    }

    @Test
    void aHardBillingLimitCountsTheSameAsAnEmptyBalance() {
        ProviderError error = OpenAIService.providerError(429,
                "{\"error\":{\"type\":\"billing_error\",\"code\":\"billing_hard_limit_reached\"}}");

        assertTrue(error.isQuotaExhausted(), "a limit reached is as unpayable tonight as a balance at zero");
    }

    @Test
    void aBodyThatIsNotAnEnvelopeYieldsNoVerdict() {
        // An ALB or proxy page, a half-received stream, and nothing at all. None of them says
        // anything about quota, and guessing from the status is exactly what must not happen.
        for (String body : new String[]{"<html><body>502 Bad Gateway</body></html>", "{\"nope\":1}", "", null}) {
            ProviderError error = OpenAIService.providerError(429, body);

            assertNull(error.type(), "nothing to read out of: " + body);
            assertNull(error.code(), "nothing to read out of: " + body);
            assertFalse(error.isQuotaExhausted(),
                    "an unreadable body is a transient failure, not a billing verdict: " + body);
            assertEquals(429, error.status());
        }
    }

    @Test
    void theAnswerThrowsOnlyWhenTheAccountIsOutOfCredit() {
        SchemaAnswer outOfCredit = new SchemaAnswer("{}", OpenAIService.providerError(429, OUT_OF_CREDIT));
        OpenAIQuotaException thrown =
                assertThrows(OpenAIQuotaException.class, outOfCredit::jsonOrThrowWhenOutOfCredit);
        assertTrue(thrown.getMessage().contains("credit_balance_exhausted"),
                "the code identifies it in a log; the provider's prose stays out");
        assertFalse(thrown.getMessage().contains("credit balance is too low"),
                "the message can quote the request — it must never reach a log line");

        SchemaAnswer rateLimited = new SchemaAnswer("{}", OpenAIService.providerError(429, RATE_LIMITED));
        assertEquals("{}", rateLimited.jsonOrThrowWhenOutOfCredit(),
                "every other failure keeps the empty-body contract its callers are built on");

        String answer = "{\"mentions\":[]}";
        assertSame(answer, new SchemaAnswer(answer, null).jsonOrThrowWhenOutOfCredit());
    }
}
