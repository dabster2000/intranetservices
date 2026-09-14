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
 * defaulting to CLIENT. This is the hard filter that keeps PARTNERs — and, since
 * 2026-09-14, PROSPECTs — out of every caller that doesn't explicitly request them.
 *
 * <p><b>The parameter is a String, not a {@link ClientType}.</b> It used to be the enum,
 * and RESTEasy's own coercion was the validation. It became a comma list when the accounts
 * list, the lead form's picker and the sector tabs needed {@code ?type=CLIENT,PROSPECT} —
 * a prospect is an account like any other, it has simply never been billed. The coercion
 * moved into {@code ClientResource.parseTypes}, where an unknown value is a 400 and an
 * empty or absent one degrades to CLIENT alone; {@code ClientTypeFilterTest} holds that
 * behaviour, which is the part that actually matters. What this test still guards is the
 * DEFAULT, because that is what makes a third enum value safe: every existing consumer
 * keeps excluding it until it opts in.
 *
 * Uses reflection so the test runs without booting Quarkus / loading runtime
 * secrets. The runtime behaviour (filtering by type) is exercised in
 * ClientResourceTypeFilterContractTest when @QuarkusTest can boot.
 *
 * SPEC-INV-001 §3.4, §8.8; relationships spec §5.
 */
class ClientResourceTypeFilterTest {

    @Test
    void findAll_method_exists_and_declares_type_query_param_defaulting_to_CLIENT() {
        Method findAll = findFindAllMethod();
        assertNotNull(findAll, "ClientResource must expose a findAll method on GET /clients");

        Parameter typeParam = findParameter(findAll, "type");
        assertNotNull(typeParam,
                "findAll must declare a `type` query parameter to filter CLIENT vs PARTNER");

        assertEquals(String.class, typeParam.getType(),
                "`type` is a comma list parsed by ClientResource.parseTypes, so the parameter "
                        + "is a String — the enum coercion that used to be RESTEasy's job moved "
                        + "there, and ClientTypeFilterTest holds it");

        QueryParam qp = typeParam.getAnnotation(QueryParam.class);
        assertNotNull(qp, "`type` parameter must be annotated with @QueryParam");
        assertEquals("type", qp.value(), "@QueryParam value must be \"type\"");

        DefaultValue dv = typeParam.getAnnotation(DefaultValue.class);
        assertNotNull(dv, "`type` parameter must have @DefaultValue to hard-filter PARTNERs and PROSPECTs out of callers that don't specify");
        assertEquals("CLIENT", dv.value(),
                "@DefaultValue for `type` must be \"CLIENT\" — backward compat, and the reason a "
                        + "third enum value is safe: nothing sees a prospect until it asks");
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
