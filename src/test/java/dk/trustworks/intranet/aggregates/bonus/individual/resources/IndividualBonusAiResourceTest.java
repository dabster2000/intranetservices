package dk.trustworks.intranet.aggregates.bonus.individual.resources;

import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusGenerateRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusGenerateResponse;
import dk.trustworks.intranet.aggregates.bonus.individual.model.*;
import dk.trustworks.intranet.aggregates.bonus.individual.services.IndividualBonusAiService;
import jakarta.annotation.security.RolesAllowed;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IndividualBonusAiResourceTest {

    @Test
    void endpointDelegatesWithoutPersistence_andReturnsProposal() {
        IndividualBonusAiService aiService = mock(IndividualBonusAiService.class);
        IndividualBonusGenerateRequest request = new IndividualBonusGenerateRequest(
                "employee", "20% above one million", null);
        IndividualBonusGenerateResponse generated = new IndividualBonusGenerateResponse(spec(), List.of(), false);
        when(aiService.generate(request)).thenReturn(generated);

        IndividualBonusResource resource = new IndividualBonusResource();
        resource.aiService = aiService;

        assertSame(generated, resource.generateFromText(request));
        verify(aiService).generate(request);
        verifyNoMoreInteractions(aiService);
    }

    @Test
    void endpointExplicitlyRequiresBonusWriteScope() throws Exception {
        RolesAllowed annotation = IndividualBonusResource.class
                .getMethod("generateFromText", IndividualBonusGenerateRequest.class)
                .getAnnotation(RolesAllowed.class);

        assertNotNull(annotation);
        assertEquals(List.of("bonus:write"), Arrays.asList(annotation.value()));
    }

    private static Spec spec() {
        return new Spec(Basis.OWN_INVOICED_REVENUE, "FISCAL_YEAR_SUM",
                List.of(new Tier(BigDecimal.ZERO, null, new BigDecimal("0.2"))),
                new ProRating(false), null, false, null,
                new Schedule(Cadence.YEARLY, new Yearly(1), null, null), null);
    }
}
