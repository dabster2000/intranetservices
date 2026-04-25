package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.entities.RecruitmentStatusEntity;
import dk.trustworks.intranet.recruitmentservice.domain.enums.RecruitmentStatusValue;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ScopeKind;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class RecruitmentStatusServiceTest {

    @Inject RecruitmentStatusService service;

    @Test
    @TestTransaction
    void upsertCreatesThenUpdates() {
        String actor = UUID.randomUUID().toString();
        service.upsert(ScopeKind.PRACTICE, "DEV", RecruitmentStatusValue.PASSIVE, "Q4 freeze", actor);
        service.upsert(ScopeKind.PRACTICE, "DEV", RecruitmentStatusValue.ACTIVE, "Reopened", actor);

        RecruitmentStatusEntity row = service.find(ScopeKind.PRACTICE, "DEV").orElseThrow();
        assertEquals(RecruitmentStatusValue.ACTIVE, row.status);
        assertEquals("Reopened", row.reason);
        assertEquals(actor, row.changedByUuid);
    }
}
