package dk.trustworks.intranet.sales.services;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

/** Database-free regression coverage for sales-lead deletion ordering. */
class SalesServiceDeleteTest {

    private static final String LEAD_UUID = "1342f9f4-6c1e-4cc4-8c31-a619e17a40a0";

    @Test
    void delete_withAssignedConsultants_removesAssignmentsBeforeLead() {
        SalesService service = new SalesService();
        List<String> operations = new ArrayList<>();

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.delete("lead.uuid = ?1", LEAD_UUID))
                    .thenAnswer(ignored -> {
                        operations.add("consultants");
                        return 2L;
                    });
            panache.when(() -> PanacheEntityBase.deleteById(LEAD_UUID))
                    .thenAnswer(ignored -> {
                        operations.add("lead");
                        return true;
                    });

            service.delete(LEAD_UUID);

            panache.verify(
                    () -> PanacheEntityBase.delete("lead.uuid = ?1", LEAD_UUID),
                    times(1));
            panache.verify(() -> PanacheEntityBase.deleteById(LEAD_UUID), times(1));
        }

        assertEquals(List.of("consultants", "lead"), operations);
    }

    @Test
    void delete_missingLead_remainsIdempotent() {
        SalesService service = new SalesService();

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.delete("lead.uuid = ?1", LEAD_UUID))
                    .thenReturn(0L);
            panache.when(() -> PanacheEntityBase.deleteById(LEAD_UUID))
                    .thenReturn(false);

            assertDoesNotThrow(() -> service.delete(LEAD_UUID));

            panache.verify(
                    () -> PanacheEntityBase.delete("lead.uuid = ?1", LEAD_UUID),
                    times(1));
            panache.verify(() -> PanacheEntityBase.deleteById(LEAD_UUID), times(1));
        }
    }
}
