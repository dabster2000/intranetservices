package dk.trustworks.intranet.aggregates.bonus.individual.dsl;

import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.introspection.JexlPermissions;
import org.apache.commons.jexl3.introspection.JexlSandbox;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A locked-down Apache Commons JEXL 3 engine for the individual-bonus {@code formula} escape hatch —
 * payroll money code, so security and fail-safe behaviour are non-negotiable.
 * <p>
 * <b>Sandbox (defence in depth):</b>
 * <ul>
 *   <li>{@link JexlPermissions#RESTRICTED} — the JEXL ≥3.3 deny-list: {@code Runtime}, {@code System},
 *       {@code ProcessBuilder}, {@code Class}, {@code ClassLoader}, reflection, IO/net are invisible to the
 *       introspector, so {@code ''.class.forName(...)} / {@code new('java.lang.Runtime')} cannot resolve.</li>
 *   <li>a block-mode {@link JexlSandbox} ({@code new JexlSandbox(false)}) that permits ONLY
 *       {@link BonusFunctions}'s seven named helpers ({@code tier}/{@code min}/{@code max}/{@code round}/
 *       {@code floor}/{@code ceil}/{@code abs}); every OTHER method or property access — on the helper, on a
 *       {@link BigDecimal}, on a {@link String} — is denied. {@code strict}, {@code safe=false},
 *       {@code silent=false}: unknown variables / null derefs are hard errors, never silent nulls.</li>
 *   <li>the per-eval {@link BonusJexlContext} only exposes the allow-listed variables and refuses writes,
 *       so a formula can neither read an un-curated fact nor mutate state.</li>
 *   <li>a hardened {@link JexlFeatures} pinned LOCALLY (not the mutable process-wide default): loops,
 *       lambdas, {@code new}, assignment, {@code #pragma} and annotations DO NOT PARSE — a bonus formula is a
 *       pure arithmetic expression, so the iterative-growth and side-effect surfaces a cooperative timeout
 *       cannot preempt are removed structurally (a runaway {@code while} is rejected at write time).</li>
 * </ul>
 * <b>Runaway / timeout / resource bounds:</b> as a backstop the JVM gives JEXL no hard CPU limit, so each
 * evaluation still runs on a bounded {@code cancellable} worker thread (from a FIXED pool, so a flood cannot
 * exhaust threads) with a wall-clock timeout. {@code round(x, n)} caps its scale and the result is rejected
 * above {@value #MAX_RESULT_PRECISION} significant digits, so a single expression cannot allocate a giant
 * number. The compiled-script cache is a size-bounded LRU (untrusted formulas cannot grow the heap). Because
 * DB-backed variables are pre-resolved on the CALLER thread (the worker sees only a pure map), the worker
 * never touches the request-scoped {@code EntityManager}. Formula length is capped ({@value #MAX_FORMULA_LENGTH}).
 * <p>
 * <b>Result:</b> coerced to a {@link BigDecimal} at øre scale; a {@code null} / non-numeric / non-finite
 * result throws {@link BonusFormulaException} — never a guessed amount.
 * <p>
 * The engine and executor are shared, thread-safe statics (built once, reused); only the timeout is
 * per-instance (config-driven, overridable via the package-visible constructor for fast unit tests).
 */
@ApplicationScoped
public class BonusFormulaEngine {

    /** Write-time / compile-time cap on formula length — a crude but effective abuse guard. */
    public static final int MAX_FORMULA_LENGTH = 2000;

    private static final int MONEY_SCALE = 2; // øre

    /** Reject an absurd result magnitude — a plausible gross payroll bonus never has this many digits. */
    private static final int MAX_RESULT_PRECISION = 30;

    /** Bound on the compiled-script cache so authenticated distinct-formula flooding cannot grow the heap. */
    private static final int MAX_CACHED_SCRIPTS = 1_000;

    /** Shared, thread-safe, immutable engine — built once. */
    private static final JexlEngine JEXL = buildEngine();

    /**
     * FIXED daemon pool for bounded, cancellable evaluation. A fixed size caps the thread count so an
     * authenticated flood of concurrent (or slow) evals cannot exhaust threads — unlike a cached pool.
     * Formula eval is low-frequency and on-demand, so a small pool is ample.
     */
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()), r -> {
                Thread t = new Thread(r, "bonus-formula-eval");
                t.setDaemon(true);
                return t;
            });

    /**
     * Compiled-script cache — a SIZE-BOUNDED LRU (synchronized) keyed by formula text. Bounding it is
     * required because {@link #compile} runs on the untrusted write/preview path (before the variable
     * allow-list check), so submitting many distinct formulas must not grow the heap without limit.
     */
    private static final Map<String, JexlScript> SCRIPT_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JexlScript> eldest) {
                    return size() > MAX_CACHED_SCRIPTS;
                }
            });

    @ConfigProperty(name = "individual-bonus.formula.timeout-ms", defaultValue = "2000")
    long timeoutMillis;

    public BonusFormulaEngine() {
    }

    /** Test seam: build with an explicit timeout, no CDI. */
    BonusFormulaEngine(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    private static JexlEngine buildEngine() {
        JexlSandbox sandbox = new JexlSandbox(false); // block-mode: nothing is callable unless allow-listed
        // permissions(class, read=false, write=false, execute=true): block-list read/write (so an empty
        // read/write allow-set can never mean "allow all" on the functor) and allow-list ONLY the seven
        // execute helpers. Closes the latent read/write allow-all surface on the BonusFunctions functor.
        sandbox.permissions(BonusFunctions.class.getName(), false, false, true)
                .execute("tier", "min", "max", "round", "floor", "ceil", "abs");
        // RESTRICTED keeps the dangerous java.* classes invisible; it must be COMPOSED with an explicit
        // allowance for the helper package or the introspector cannot even see BonusFunctions' methods
        // (namespace calls would fail "unsolvable"). The block-mode sandbox above then narrows that package
        // down to exactly the seven helper methods — so composing the package in does NOT widen the surface.
        JexlPermissions perms = JexlPermissions.RESTRICTED.compose(BonusFunctions.class.getPackageName() + ".*");
        // A bonus formula is a pure arithmetic EXPRESSION, not a program. Pin a hardened feature set LOCALLY
        // (not relying on JexlBuilder's mutable process-wide default) so loops/lambdas/assignment/new/pragma/
        // annotation simply DO NOT PARSE — structurally removing the iterative-growth and side-effect surfaces
        // that a 2 s cooperative timeout cannot preempt. A runaway `while(...)` is now rejected at write time.
        JexlFeatures features = new JexlFeatures()
                .loops(false)             // no while/for → no iterative CPU/heap growth
                .lambda(false)            // no lambdas → no recursion
                .newInstance(false)       // no new('...') construction
                .sideEffect(false)        // formulas are read-only …
                .sideEffectGlobal(false)  // … and may not mutate context state
                .pragma(false)            // no #pragma (no namespace/import injection)
                .annotation(false);       // no @annotations
        return new JexlBuilder()
                .permissions(perms)
                .sandbox(sandbox)
                .features(features)
                .strict(true)      // unknown variable / method / function → error (allow-list enforcement)
                .safe(false)       // dereferencing null → error, never a silent null
                .silent(false)     // evaluation errors surface as exceptions
                .cancellable(true) // honour thread interruption (the timeout guard)
                .create();
    }

    // --- write-time validation -------------------------------------------------------------------

    /**
     * Validate a formula at WRITE time: enforce length, compile it with the sandboxed engine (catches
     * syntax and some permission errors), and reject any reference to a variable outside
     * {@link BonusContext#ALLOWED_VARIABLES} (or a nested property of one). Throws
     * {@link BonusFormulaException} (a 400) on any failure. The sandbox still enforces the rest at eval.
     */
    public void validate(String formula) {
        JexlScript script = compile(formula);
        for (List<String> path : script.getVariables()) {
            if (path == null || path.isEmpty()) continue;
            String root = path.get(0);
            if (path.size() > 1 || !BonusContext.ALLOWED_VARIABLES.contains(root)) {
                throw new BonusFormulaException("Formula references a disallowed variable: '"
                        + String.join(".", path) + "'. Allowed variables: " + BonusContext.ALLOWED_VARIABLES);
            }
        }
    }

    // --- evaluation ------------------------------------------------------------------------------

    /**
     * Evaluate {@code spec.formula()} against the curated facts in {@code ctx} and return the FY earned
     * scalar (gross DKK, øre scale). NO pro-rating is applied (the formula owns that); the caller applies
     * any {@code cap}. Every failure mode (syntax, disallowed reference, timeout/runaway, null / non-numeric
     * / non-finite result, arithmetic error) throws {@link BonusFormulaException} — never a guessed number.
     */
    public BigDecimal evaluate(Spec spec, BonusContext ctx) {
        JexlScript script = compile(spec.formula());

        // Pre-resolve ONLY the referenced allow-listed variables — on the CALLER thread, so the request-
        // scoped EntityManager is never touched from the worker thread. This is the "lazy" resolution: a
        // formula using only `production` triggers only that one query.
        Map<String, Object> vars = new HashMap<>();
        for (String name : referencedVariables(script)) {
            vars.put(name, ctx.resolveVariable(name));
        }
        BonusJexlContext context = new BonusJexlContext(vars, new BonusFunctions(ctx.tierTable()));

        Object result = runBounded(script, context);
        return coerceToMoney(result);
    }

    /** The allow-listed, single-identifier variables actually referenced by the script. */
    private static Set<String> referencedVariables(JexlScript script) {
        Set<String> out = new HashSet<>();
        for (List<String> path : script.getVariables()) {
            if (path != null && path.size() == 1 && BonusContext.ALLOWED_VARIABLES.contains(path.get(0))) {
                out.add(path.get(0));
            }
        }
        return out;
    }

    /** Run the script on a bounded, cancellable worker thread; map every failure to a fail-safe exception. */
    private Object runBounded(JexlScript script, BonusJexlContext context) {
        long budget = timeoutMillis > 0 ? timeoutMillis : 2000;
        Future<Object> future = EXEC.submit(() -> script.execute(context));
        try {
            return future.get(budget, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true); // interrupt → JEXL (cancellable) aborts at the next loop/statement check
            throw new BonusFormulaException("Formula evaluation timed out after " + budget + " ms");
        } catch (ExecutionException e) {
            throw new BonusFormulaException("Formula evaluation failed: " + rootMessage(e.getCause()), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new BonusFormulaException("Formula evaluation was interrupted");
        }
    }

    /** Coerce the script result to a BigDecimal at øre scale; fail safe on null / non-numeric / non-finite. */
    private static BigDecimal coerceToMoney(Object result) {
        if (result == null) {
            throw new BonusFormulaException("Formula produced no value (null) — refusing to pay a guessed amount");
        }
        BigDecimal bd;
        if (result instanceof BigDecimal x) {
            bd = x;
        } else if (result instanceof BigInteger x) {
            bd = new BigDecimal(x);
        } else if (result instanceof Double || result instanceof Float) {
            double d = ((Number) result).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new BonusFormulaException("Formula produced a non-finite number (" + d + ")");
            }
            bd = BigDecimal.valueOf(d);
        } else if (result instanceof Number n) {
            bd = BigDecimal.valueOf(n.longValue());
        } else {
            throw new BonusFormulaException("Formula must return a number but returned "
                    + result.getClass().getSimpleName());
        }
        // Fail safe on an absurd magnitude — no real gross bonus has this many digits, and it guards
        // against a formula that built a huge BigInteger/BigDecimal before this coercion.
        if (bd.precision() > MAX_RESULT_PRECISION) {
            throw new BonusFormulaException("Formula produced an implausibly large number ("
                    + bd.precision() + " significant digits) — refusing to pay a guessed amount");
        }
        return bd.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private JexlScript compile(String formula) {
        if (formula == null || formula.isBlank()) {
            throw new BonusFormulaException("Formula must not be empty");
        }
        if (formula.length() > MAX_FORMULA_LENGTH) {
            throw new BonusFormulaException("Formula exceeds the maximum length of " + MAX_FORMULA_LENGTH
                    + " characters");
        }
        JexlScript cached = SCRIPT_CACHE.get(formula);
        if (cached != null) return cached;
        JexlScript compiled;
        try {
            compiled = JEXL.createScript(formula);
        } catch (RuntimeException e) {
            // Rejected features (loops/lambda/new/pragma/…) surface here as JexlException at parse time.
            throw new BonusFormulaException("Invalid formula: " + rootMessage(e), e);
        }
        SCRIPT_CACHE.put(formula, compiled); // bounded LRU — evicts the eldest past MAX_CACHED_SCRIPTS
        return compiled;
    }

    private static String rootMessage(Throwable t) {
        if (t == null) return "unknown error";
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg != null && !msg.isBlank() ? msg : cur.getClass().getSimpleName();
    }
}
