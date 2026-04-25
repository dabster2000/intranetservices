package dk.trustworks.intranet.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminScopeAugmentorRecruitmentTest {

    @Test
    void allFiveRecruitmentScopesAreInTheCanonicalScopeSet() {
        Set<String> all = AdminScopeAugmentor.ALL_SCOPES;
        assertTrue(all.contains("recruitment:read"));
        assertTrue(all.contains("recruitment:write"));
        assertTrue(all.contains("recruitment:admin"));
        assertTrue(all.contains("recruitment:interview"));
        assertTrue(all.contains("recruitment:offer"));
    }
}
