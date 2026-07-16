package dk.trustworks.intranet.aggregates.practices.jobs;

import dk.trustworks.intranet.aggregates.practices.services.PracticeCostBasisRefreshService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSR-352 {@code JobContext.getProperties()} exposes only the job XML's job-level properties, never
 * the raw runtime parameters passed to {@code jobOperator.start(job, parameters)}. The batchlet reads
 * its expected-request identity through that context, so the job XML MUST map every jobParameter into
 * a job-level property with exactly the names {@code ExpectedRequest} writes/reads. Without this
 * mapping every scheduler and operator start fails with
 * "expected cost request id must be a positive integer" before any claim is attempted.
 */
class PracticeCostBasisRefreshJobXmlContractTest {

    @Test
    void jobXmlMapsEveryExpectedRequestParameterIntoJobLevelProperties() throws IOException {
        String xml = jobXml();
        for (String name : new String[]{
                PracticeCostBasisRefreshService.ExpectedRequest.REQUEST_ID_PROPERTY,
                PracticeCostBasisRefreshService.ExpectedRequest.REQUEST_KEY_PROPERTY,
                PracticeCostBasisRefreshService.ExpectedRequest.INPUT_VECTOR_PROPERTY}) {
            assertTrue(xml.contains("<property name=\"" + name
                            + "\" value=\"#{jobParameters['" + name + "']}\"/>"),
                    "practice-cost-basis-refresh.xml must map jobParameters['" + name
                            + "'] into a job-level property named " + name);
        }
    }

    @Test
    void mappedJobPropertiesRoundTripThroughExpectedRequestParsing() {
        var expected = new PracticeCostBasisRefreshService.ExpectedRequest(
                java.math.BigInteger.valueOf(42), "a".repeat(64), "b".repeat(64));
        var parsed = PracticeCostBasisRefreshService.ExpectedRequest.fromJobProperties(
                expected.toJobProperties());
        assertTrue(expected.requestId().equals(parsed.requestId())
                        && expected.requestKey().equals(parsed.requestKey())
                        && expected.inputVectorFingerprint().equals(parsed.inputVectorFingerprint()),
                "toJobProperties/fromJobProperties must round-trip the full expected-request identity");
    }

    private static String jobXml() throws IOException {
        try (InputStream stream = PracticeCostBasisRefreshJobXmlContractTest.class
                .getResourceAsStream("/META-INF/batch-jobs/practice-cost-basis-refresh.xml")) {
            assertNotNull(stream, "practice-cost-basis-refresh.xml must be on the classpath");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
