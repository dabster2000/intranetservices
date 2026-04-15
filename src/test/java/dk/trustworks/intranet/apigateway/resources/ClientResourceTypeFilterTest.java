package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the ClientResource.findAll endpoint declares a `type` query parameter
 * of type {@link ClientType} with default value CLIENT. This is the hard filter
 * that keeps PARTNERs out of every caller that doesn't explicitly request them.
 *
 * Uses reflection so the test runs without booting Quarkus / loading runtime
 * secrets. The runtime behaviour (filtering by type + active) is exercised in
 * ClientResourceTypeFilterContractTest when @QuarkusTest can boot.
 *
 * SPEC-INV-001 §3.4, §8.8.
 */
class ClientResourceTypeFilterTest {

    @Test
    void findAll_method_exists_and_declares_type_query_param_defaulting_to_CLIENT() {
        Method findAll = findFindAllMethod();
        assertNotNull(findAll, "ClientResource must expose a findAll method on GET /clients");

        Parameter typeParam = findParameter(findAll, "type");
        assertNotNull(typeParam,
                "findAll must declare a `type` query parameter to filter CLIENT vs PARTNER");

        assertEquals(ClientType.class, typeParam.getType(),
                "`type` query parameter must be ClientType, not String");

        QueryParam qp = typeParam.getAnnotation(QueryParam.class);
        assertNotNull(qp, "`type` parameter must be annotated with @QueryParam");
        assertEquals("type", qp.value(), "@QueryParam value must be \"type\"");

        DefaultValue dv = typeParam.getAnnotation(DefaultValue.class);
        assertNotNull(dv, "`type` parameter must have @DefaultValue to hard-filter PARTNERs out of callers that don't specify");
        assertEquals("CLIENT", dv.value(),
                "@DefaultValue for `type` must be \"CLIENT\" — backward compat + hard PARTNER filter");
    }

    @Test
    void findAll_declares_active_query_param_as_nullable_Boolean() {
        Method findAll = findFindAllMethod();
        Parameter activeParam = findParameter(findAll, "active");
        assertNotNull(activeParam,
                "findAll must declare an `active` query parameter so callers can filter by active state");

        QueryParam qp = activeParam.getAnnotation(QueryParam.class);
        assertNotNull(qp, "`active` parameter must be annotated with @QueryParam");
        assertEquals("active", qp.value());

        assertEquals(Boolean.class, activeParam.getType(),
                "`active` must be the wrapper Boolean (nullable): omit = return both active and inactive, " +
                "preserving prior /clients default. true/false filters explicitly.");

        assertNull(activeParam.getAnnotation(DefaultValue.class),
                "`active` must NOT have @DefaultValue — omission means \"no active filter\" " +
                "so existing callers that fetched all clients keep working.");
    }

    private static Method findFindAllMethod() {
        for (Method m : ClientResource.class.getDeclaredMethods()) {
            if ("findAll".equals(m.getName())) {
                return m;
            }
        }
        return null;
    }

    private static Parameter findParameter(Method method, String queryParamName) {
        for (Parameter p : method.getParameters()) {
            for (Annotation a : p.getAnnotations()) {
                if (a instanceof QueryParam qp && queryParamName.equals(qp.value())) {
                    return p;
                }
            }
        }
        return null;
    }
}
