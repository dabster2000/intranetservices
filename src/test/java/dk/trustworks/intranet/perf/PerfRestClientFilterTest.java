package dk.trustworks.intranet.perf;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerfRestClientFilterTest {

    @Test
    void apiLabel_mapsKnownHostsToBoundedLabels() {
        assertEquals("economic", PerfRestClientFilter.apiLabel(URI.create("https://apis.e-conomic.com/q2capi/v5.1.0/invoices/drafts")));
        assertEquals("graph", PerfRestClientFilter.apiLabel(URI.create("https://graph.microsoft.com/v1.0/me")));
        assertEquals("openai", PerfRestClientFilter.apiLabel(URI.create("https://api.openai.com/v1/responses")));
        assertEquals("nextsign", PerfRestClientFilter.apiLabel(URI.create("https://api.nextsign.dk/api/v1/foo")));
        assertEquals("cvr", PerfRestClientFilter.apiLabel(URI.create("https://virkdata.dk/cvr/123")));
    }

    @Test
    void operationLabel_collapsesNumericIdsToBoundIt() {
        assertEquals("GET invoices drafts {id}",
                PerfRestClientFilter.operationLabel("GET", URI.create("https://apis.e-conomic.com/q2capi/v5.1.0/invoices/drafts/123456")));
    }

    @Test
    void operationLabel_collapsesDateSegmentsToBoundId() {
        assertEquals("GET accounting-years {id} entries",
                PerfRestClientFilter.operationLabel("GET",
                        URI.create("https://apis.e-conomic.com/q2capi/v5.1.0/accounting-years/2025-07-01/entries")));
    }

    @Test
    void statusClass_bucketsByHundreds() {
        assertEquals("2xx", PerfRestClientFilter.statusClass(204));
        assertEquals("4xx", PerfRestClientFilter.statusClass(404));
        assertEquals("5xx", PerfRestClientFilter.statusClass(503));
    }
}
