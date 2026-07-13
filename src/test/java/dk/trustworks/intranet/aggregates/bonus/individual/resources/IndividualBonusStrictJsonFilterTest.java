package dk.trustworks.intranet.aggregates.bonus.individual.resources;

import dk.trustworks.intranet.aggregates.bonus.individual.exceptions.IndividualBonusException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndividualBonusStrictJsonFilterTest {

    private final IndividualBonusStrictJsonFilter filter = new IndividualBonusStrictJsonFilter();

    @Test
    void monthlySpec_requiresExplicitBooleanPension() {
        IndividualBonusException missing = assertThrows(IndividualBonusException.class,
                () -> filter.filter(context("{\"spec\":{\"aggregation\":\"CALENDAR_MONTH\"}}")));
        assertEquals("MONTHLY_PENSION_REQUIRED", missing.code());
        assertEquals("spec.pension", missing.field());

        IndividualBonusException wrongType = assertThrows(IndividualBonusException.class,
                () -> filter.filter(context("{\"spec\":{\"aggregation\":\"CALENDAR_MONTH\",\"pension\":\"false\"}}")));
        assertEquals("MONTHLY_PENSION_REQUIRED", wrongType.code());

        assertDoesNotThrow(() -> filter.filter(context(
                "{\"spec\":{\"aggregation\":\"CALENDAR_MONTH\",\"pension\":false}}")));
    }

    @Test
    void duplicateKeysAreRejectedBeforeBinding() {
        IndividualBonusException failure = assertThrows(IndividualBonusException.class,
                () -> filter.filter(context("{\"name\":\"a\",\"name\":\"b\"}")));
        assertEquals("INVALID_JSON", failure.code());
    }

    @Test
    void payloadOver256KiBIsRejected() {
        String body = "{\"value\":\"" + "x".repeat(IndividualBonusStrictJsonFilter.MAX_BYTES) + "\"}";
        IndividualBonusException failure = assertThrows(IndividualBonusException.class,
                () -> filter.filter(context(body)));
        assertEquals("REQUEST_BODY_TOO_LARGE", failure.code());
    }

    private static ContainerRequestContext context(String body) {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn("individual-bonuses/preview");
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(context.hasEntity()).thenReturn(true);
        when(context.getMediaType()).thenReturn(MediaType.APPLICATION_JSON_TYPE);
        when(context.getEntityStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return context;
    }
}
