package dk.trustworks.intranet.aggregates.practices.resources;

import dk.trustworks.intranet.aggregates.practices.resources.ContributionUnavailableExceptionMapper.ContributionUnavailableResponse;
import dk.trustworks.intranet.aggregates.practices.services.ContributionUnavailableException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ContributionUnavailableExceptionMapperTest {

    @ParameterizedTest
    @ValueSource(strings = {
            ContributionUnavailableException.PUBLICATION_UNAVAILABLE,
            ContributionUnavailableException.PUBLICATION_DISABLED,
            ContributionUnavailableException.QUERY_TIMEOUT
    })
    void emitsOnlyTheAllowListedCodeInAJson503(String code) {
        ContributionUnavailableException exception = new ContributionUnavailableException(code);
        Response response = new ContributionUnavailableExceptionMapper().toResponse(exception);

        assertEquals(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
        assertEquals(new ContributionUnavailableResponse(code), response.getEntity());
        assertArrayEquals(
                new String[]{"code"},
                Arrays.stream(ContributionUnavailableResponse.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toArray(String[]::new));
    }
}
