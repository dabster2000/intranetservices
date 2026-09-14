package dk.trustworks.intranet.apis.openai;

import dk.trustworks.intranet.apis.openai.OpenAIService.ProviderError;

/**
 * The AI provider refused the call because the account cannot pay for it — an exhausted
 * prepaid credit balance, or a billing limit reached.
 *
 * <p><b>Why this is an exception when nothing else here is.</b> {@link OpenAIService}
 * reports every other failure as an empty body, on purpose: a timeout, a busy model and a
 * blown token budget are all "no answer this time", and a caller can only degrade. An
 * exhausted balance is a different fact. It is not this call's failure but every call's, it
 * will be answered identically for as long as the balance is zero, and no retry tonight can
 * clear it — the fix is a payment. A loop that treats it as an ordinary failure pays for one
 * refusal per item and then reports N anonymous failures, which is exactly what the CRM
 * Slack lanes did on 2026-09-14: three channels, three 429s, and a run row saying
 * "3 failures" with no reason in it.
 *
 * <p>So the lanes stop at the first one and name it, the same posture they already take for
 * a misconfigured Slack app, and for the same reason: every remaining item would answer the
 * same way.
 *
 * <p>Carries the envelope's {@code type} and {@code code} only. The provider's prose can
 * quote the request, and this message reaches logs.
 */
public class OpenAIQuotaException extends RuntimeException {

    private final transient ProviderError providerError;

    public OpenAIQuotaException(ProviderError providerError) {
        super("the OpenAI account is out of credit (" + providerError + ")");
        this.providerError = providerError;
    }

    public ProviderError getProviderError() {
        return providerError;
    }
}
