package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.apigateway.dto.EmployeeBonusBasisDTO;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.security.ScopeEnforced;
import jakarta.annotation.security.RolesAllowed;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 9.3 structural pins for the bonus domain. The query-level filtering in
 * {@link YourPartOfTrustworksResource} runs through Panache/EntityManager and is
 * verified live at the staging deploy (V9.1/V9.6 per plan principle P7); what is
 * pinned here is everything that must not regress silently at compile time:
 * the aggregate surfaces stay refused for bounded actors, and the basis payload
 * stays trimmed.
 */
class BonusScopeEnforcementTest {

    @Test
    void twBonusResourceIsScopeEnforced() {
        assertTrue(TwBonusResource.class.isAnnotationPresent(ScopeEnforced.class),
                "Phase 9.3: /bonus/tw serves company-wide aggregates — a bounded actor "
                        + "must get 403, never a partial figure presented as a total");
        RolesAllowed gate = TwBonusResource.class.getAnnotation(RolesAllowed.class);
        assertEquals(Set.of("bonus:read"), Set.of(gate.value()));
    }

    @Test
    void salaryRecalcEndpointsAreScopeEnforced() {
        List<String> recalcMethods = List.of("recalcSalaryBulk", "recalcSalaryUser");
        for (String name : recalcMethods) {
            Method method = Arrays.stream(YourPartOfTrustworksResource.class.getMethods())
                    .filter(m -> m.getName().equals(name))
                    .findFirst().orElseThrow();
            assertTrue(method.isAnnotationPresent(ScopeEnforced.class),
                    name + " triggers company-wide salary recalculation — bounded actors are refused");
        }
    }

    @Test
    void basisPayloadCarriesTheTrimmedUserProjectionOnly() {
        // Owner decision 2026-08-06 (access-intent Decision 8): the basis payload
        // stops embedding the full User entity (phone, birthday, gender, pension
        // rode along). The DTO must reference the slim projection, not User.
        Field userField = Arrays.stream(EmployeeBonusBasisDTO.class.getDeclaredFields())
                .filter(f -> f.getName().equals("user"))
                .findFirst().orElseThrow();
        assertEquals(EmployeeBonusBasisDTO.BasisUser.class, userField.getType(),
                "the basis payload must never embed the full User entity again");

        Set<String> fields = Arrays.stream(EmployeeBonusBasisDTO.BasisUser.class.getDeclaredFields())
                .map(Field::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("uuid", "username", "firstname", "lastname"), fields,
                "BasisUser is identity-only; adding a field here is a payload-shape decision "
                        + "for the owner (and twBonusEligible in particular would flip the "
                        + "dashboard's latent eligibility check — findings 2026-08-06)");
    }

    @Test
    void basisUserProjectionCopiesIdentityFields() {
        User user = new User();
        user.setUuid("u-1");
        user.setUsername("hans.tester");
        user.setFirstname("Hans");
        user.setLastname("Tester");

        EmployeeBonusBasisDTO.BasisUser projected = EmployeeBonusBasisDTO.BasisUser.from(user);

        assertEquals("u-1", projected.getUuid());
        assertEquals("hans.tester", projected.getUsername());
        assertEquals("Hans", projected.getFirstname());
        assertEquals("Tester", projected.getLastname());
    }

    @Test
    void yourPartReadEndpointsAreNotDenyStyleEnforced() {
        // The basis/eligibility row sets FILTER by reach (subjects into the WHERE
        // clause) rather than refusing bounded actors — an employee's own record
        // must keep flowing to the dashboard. @ScopeEnforced on either read
        // endpoint would 403 every employee's "my part" widget.
        for (String name : List.of("findByFiscalStartYear", "findMonthlyBasis")) {
            Method method = Arrays.stream(YourPartOfTrustworksResource.class.getMethods())
                    .filter(m -> m.getName().equals(name))
                    .findFirst().orElseThrow();
            assertFalse(method.isAnnotationPresent(ScopeEnforced.class),
                    name + " must filter by reach, never deny bounded actors — "
                            + "denying breaks every employee's own bonus view");
        }
        assertFalse(YourPartOfTrustworksResource.class.isAnnotationPresent(ScopeEnforced.class));
    }
}
