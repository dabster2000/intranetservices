package dk.trustworks.intranet.aggregates.invoice.economics.period;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/**
 * e-conomic AccountingYears API v2.0.1 — read-only access to accounting periods.
 *
 * <p>Auth follows every other e-conomic client here: {@code X-AppSecretToken} and
 * {@code X-AgreementGrantToken} per call, because the agreement is chosen at invocation time
 * (one per Trustworks company).
 *
 * <p>Base URL: {@code https://apis.e-conomic.com/accountingyearsapi/v2.0.1}
 *
 * <p>Deliberately NOT registered with an error mapper. Callers must treat a failure here as
 * "don't know" and carry on — this API only ever informs a pre-flight courtesy check, and turning
 * a vendor outage into a blocked invoicing run would be a far worse failure than the one it
 * prevents. See {@link AccountingPeriodPreflight}.
 */
@RegisterRestClient(configKey = "economics-accounting-years-api")
@RegisterProvider(dk.trustworks.intranet.perf.PerfRestClientFilter.class)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface EconomicsAccountingYearsApiClient {

    /**
     * Classic pagination over every accounting period in the agreement. Returns a bare JSON array
     * (not an envelope) — the cursor variant at {@code /accountingYears/periods} is the one that
     * wraps results in {@code {cursor, items}}.
     *
     * <p>Unfiltered on purpose. e-conomic's filter grammar differs between its API generations and
     * a wrong filter silently returns the wrong set, which on this path would mean confidently
     * blocking a legitimate booking. A few dozen periods per agreement fit in one or two pages, so
     * fetching them and matching the date locally is both cheaper to reason about and impossible
     * to get subtly wrong.
     *
     * @param pageSize  1–100
     * @param skipPages 0-based page index
     */
    @GET
    @Path("/accountingYears/periods/paged")
    List<EconomicsAccountingPeriod> listPeriods(
            @HeaderParam("X-AppSecretToken") String appSecret,
            @HeaderParam("X-AgreementGrantToken") String agreementGrant,
            @QueryParam("pageSize") int pageSize,
            @QueryParam("skipPages") int skipPages);
}
