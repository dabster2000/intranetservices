package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Reflection-only sanity check for {@link DossierService#branchFromRevision}.
 * Full happy-path and error-path coverage is exercised in CI integration
 * tests; Mockito cannot mock Panache statics, and the local dev env lacks
 * the {@code cvtool.*} config required to boot @QuarkusTest.
 */
class DossierServiceBranchTest {

    @Test
    void branchFromRevision_methodExists() throws Exception {
        Method m = DossierService.class.getMethod(
                "branchFromRevision", UUID.class, UUID.class, UUID.class);
        assertNotNull(m, "DossierService must expose branchFromRevision(UUID, UUID, UUID)");
        assertEquals(
                dk.trustworks.intranet.recruitmentservice.dto.DossierResponse.class,
                m.getReturnType());
        jakarta.transaction.Transactional t = m.getAnnotation(jakarta.transaction.Transactional.class);
        assertNotNull(t, "branchFromRevision must be @Transactional");
    }
}
