package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.RecruitmentStatusValue;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ScopeKind;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class RecruitmentStatusEntityTest {

    @Test
    @TestTransaction
    void persistsCompositePrimaryKey() {
        RecruitmentStatusEntity row = new RecruitmentStatusEntity();
        row.scopeKind = ScopeKind.TEAM;
        row.scopeId = "team-dev";
        row.status = RecruitmentStatusValue.ACTIVE;
        row.changedByUuid = UUID.randomUUID().toString();
        row.reason = "Initial activation";
        row.persist();

        RecruitmentStatusEntity loaded = RecruitmentStatusEntity
                .findById(new RecruitmentStatusEntity.Id(ScopeKind.TEAM, "team-dev"));
        assertNotNull(loaded);
        assertEquals(RecruitmentStatusValue.ACTIVE, loaded.status);
        assertEquals("Initial activation", loaded.reason);
    }
}
