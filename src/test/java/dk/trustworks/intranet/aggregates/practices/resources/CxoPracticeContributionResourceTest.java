package dk.trustworks.intranet.aggregates.practices.resources;

import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CxoPracticeContributionResourceTest {

    @Test
    void parserDefaultsOnlyOmissionAndAcceptsOnlyClosedUppercaseValues() {
        assertEquals(CostSource.BOOKED, CxoPracticeContributionResource.parseCostSource(null));
        assertEquals(CostSource.BOOKED, CxoPracticeContributionResource.parseCostSource(List.of("BOOKED")));
        assertEquals(CostSource.BOOKED_PLUS_DRAFT,
                CxoPracticeContributionResource.parseCostSource(List.of("BOOKED_PLUS_DRAFT")));

        assertThrows(BadRequestException.class,
                () -> CxoPracticeContributionResource.parseCostSource(List.of("")));
        assertThrows(BadRequestException.class,
                () -> CxoPracticeContributionResource.parseCostSource(List.of("booked")));
        assertThrows(BadRequestException.class,
                () -> CxoPracticeContributionResource.parseCostSource(List.of("UNKNOWN")));
        assertThrows(BadRequestException.class,
                () -> CxoPracticeContributionResource.parseCostSource(List.of("BOOKED", "BOOKED")));
    }

    @Test
    void resourceHasFrozenPathAndScope() {
        assertEquals("/practices/cxo/contribution",
                CxoPracticeContributionResource.class.getAnnotation(Path.class).value());
        assertArrayEquals(new String[]{"dashboard:read"},
                CxoPracticeContributionResource.class.getAnnotation(RolesAllowed.class).value());
    }
}
