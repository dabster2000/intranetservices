package dk.trustworks.intranet.aggregates.bonus.individual.dsl;

import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.MapContext;

import java.util.Map;

/**
 * The per-evaluation JEXL context. Seeded with ONLY the pre-resolved, allow-listed variable values (so
 * evaluation on the worker thread does no DB I/O), it:
 * <ul>
 *   <li>exposes exactly those variables — an identifier that was not seeded fails the engine's strict
 *       "unknown variable" check, so a formula can never read an un-curated fact (e.g. {@code password});</li>
 *   <li>refuses assignments — a formula is read-only and may not mutate a context variable;</li>
 *   <li>resolves the top-level (unprefixed) namespace to this rule's {@link BonusFunctions} so
 *       {@code tier(x)} / {@code min(a,b)} resolve, and NOTHING else.</li>
 * </ul>
 */
public final class BonusJexlContext extends MapContext implements JexlContext.NamespaceResolver {

    private final BonusFunctions functions;

    BonusJexlContext(Map<String, Object> resolvedVariables, BonusFunctions functions) {
        super(resolvedVariables);
        this.functions = functions;
    }

    /** Formulas are read-only — block any attempt to assign to a context variable. */
    @Override
    public void set(String name, Object value) {
        throw new BonusFormulaException("A formula may not assign to '" + name + "'");
    }

    /** Only the top-level (null/empty prefix) namespace resolves — to this rule's helper object. */
    @Override
    public Object resolveNamespace(String name) {
        return (name == null || name.isEmpty()) ? functions : null;
    }
}
