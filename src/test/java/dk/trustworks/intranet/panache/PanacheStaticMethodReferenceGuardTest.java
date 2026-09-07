package dk.trustworks.intranet.panache;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails the build on a method reference to a Panache static — the defect that
 * took {@code GET /agreements} down on 2026-09-06.
 *
 * <p>Quarkus implements the statics on {@code PanacheEntityBase} — {@code findById},
 * {@code list}, {@code count} and the rest — by rewriting <em>direct call sites</em>
 * during build-time bytecode enhancement. The inherited method itself is a stub
 * whose whole body is {@code throw implementationInjectionMissing()}. A method
 * reference is not a call site the enhancer rewrites, so
 * {@code AgreementType::findById} binds to the stub and throws at runtime:</p>
 *
 * <pre>java.lang.IllegalStateException: This method is normally automatically
 * overridden in subclasses: did you forget to annotate your entity with &#64;Entity?</pre>
 *
 * <p>The message is a red herring — the annotation is fine. Write the lambda
 * instead: {@code k -> AgreementType.findById(k)}.</p>
 *
 * <p><b>Why this test is structural.</b> Nothing observable at runtime separates
 * the two forms in the DB-free tier: with no build-time enhancement, the direct
 * call throws exactly like the method reference does, so a behavioural test
 * cannot tell a fixed line from a broken one. It compiles clean, the fast tier
 * passes, and it detonates only in the running app on the first row that reaches
 * it — three properties that let this ship. Reading the source is the only check
 * that runs in the gate and actually discriminates.</p>
 *
 * <p><b>What this guard deliberately does not do.</b> It does not exempt a
 * reference because the entity happens to declare a static of the same name.
 * {@code Bubble} declares {@code static Bubble findById(String)}, but whether
 * {@code Bubble::findById} binds to that or to the inherited
 * {@code findById(Object)} stub depends on the target functional interface —
 * a name-only exemption would wave through a stub-bound reference. Every
 * reference whose name collides with a Panache static is reported, and a human
 * confirms which overload wins.</p>
 */
class PanacheStaticMethodReferenceGuardTest {

    private static final List<Path> SOURCE_ROOTS =
            List.of(Path.of("src/main/java"), Path.of("src/test/java"));

    /**
     * Read off {@link PanacheEntityBase} on the classpath rather than written out
     * here, so a Quarkus upgrade cannot silently widen the hole. Hardcoding this
     * list against 3.15.1 would have missed {@code findByIds}, which 3.36.3 added
     * and which fails exactly like the rest.
     */
    private static final Set<String> PANACHE_STATICS = panacheStatics();

    private static final Set<String> PANACHE_BASES = Set.of(
            "PanacheEntity", "PanacheEntityBase", "PanacheMongoEntity", "PanacheMongoEntityBase");

    /**
     * Entities whose recognition this guard depends on. {@code User} and
     * {@code ExpenseAccount} are here because each shares its simple name with a
     * non-entity ({@code security.Views.User}, {@code remote.dto.economics.ExpenseAccount});
     * an implementation that resolved simple names first-wins would drop the real
     * entity on a filesystem-order coin flip and go blind on the backend's
     * most-referenced table while still reporting hundreds of entities found.
     */
    private static final Set<String> MUST_BE_RECOGNISED = Set.of(
            "User", "ExpenseAccount", "EmployeeAgreement", "AgreementType",
            "RecruitmentCandidate", "TemplateClauseEntity", "Company");

    private static final Pattern CLASS_DECL = Pattern.compile(
            "\\bclass\\s+([A-Za-z0-9_]+)\\s*(?:<[^{>]*>)?\\s*(?:extends\\s+([A-Za-z0-9_.]+))?");

    /**
     * The optional {@code <T>} tolerates an explicit type witness. The codebase
     * already writes {@code User.<User>findByIdOptional(...)} as a direct call, so
     * {@code User::<User>findByIdOptional} is one refactor away from existing.
     */
    private static final Pattern METHOD_REF = Pattern.compile(
            "\\b([A-Z][A-Za-z0-9_]*)\\s*::\\s*(?:<[^<>()]{0,120}>\\s*)?([a-zA-Z][A-Za-z0-9_]*)");

    private static final Pattern LOG_REF = Pattern.compile("\\bLog\\s*::\\s*[a-zA-Z]");

    @Test
    void noMethodReferenceTargetsAnUnenhancedPanacheStatic() throws IOException {
        Map<Path, String> sources = strippedSources();
        Set<String> entities = panacheEntities(sources.values());

        assertTrue(entities.size() > 100,
                "expected to discover the backend's Panache entities; found only " + entities.size()
                        + " — the class-declaration scan has drifted and this guard would be blind");
        Set<String> missing = new TreeSet<>(MUST_BE_RECOGNISED);
        missing.removeAll(entities);
        assertTrue(missing.isEmpty(),
                "entity discovery lost " + missing + ". A count alone cannot detect this: the guard would "
                        + "still report hundreds of entities while silently ignoring every method reference "
                        + "on the ones it dropped.");
        assertFalse(PANACHE_STATICS.isEmpty(), "no statics found on PanacheEntityBase — reflection has drifted");

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<Path, String> entry : sources.entrySet()) {
            Matcher m = METHOD_REF.matcher(entry.getValue());
            while (m.find()) {
                String cls = m.group(1);
                String method = m.group(2);
                if (entities.contains(cls) && PANACHE_STATICS.contains(method)) {
                    offenders.add(entry.getKey() + ": " + cls + "::" + method);
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "Method reference(s) to a Panache static. These compile, pass the fast tier, and throw\n"
                        + "IllegalStateException(\"did you forget to annotate your entity with @Entity?\") the first\n"
                        + "time the line executes in the running app, because Quarkus only rewrites direct call\n"
                        + "sites during build-time enhancement. Write an explicit lambda instead — e.g.\n"
                        + "  types.computeIfAbsent(key, k -> AgreementType.findById(k))\n"
                        + "If a hit is an unbound instance reference (forEach(Entity::persist)) or an entity's own\n"
                        + "overload, confirm which overload the target type selects before suppressing it:\n  "
                        + String.join("\n  ", offenders));
    }

    /**
     * {@code io.quarkus.logging.Log} is the same trap in a different package: its
     * statics are rewritten at build time and otherwise throw
     * {@code UnsupportedOperationException} from a {@code shouldFail} check set at
     * class-init. Eleven files use it today, all as direct calls. A method
     * reference would fail in production and nowhere else.
     */
    @Test
    void noMethodReferenceTargetsTheQuarkusLogStatics() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<Path, String> entry : strippedSources().entrySet()) {
            String code = entry.getValue();
            if (!code.contains("io.quarkus.logging.Log")) continue;
            Matcher m = LOG_REF.matcher(code);
            while (m.find()) {
                offenders.add(entry.getKey() + ": " + m.group().trim());
            }
        }
        assertTrue(offenders.isEmpty(),
                "Method reference(s) to io.quarkus.logging.Log. Quarkus rewrites Log call sites at build "
                        + "time; a method reference keeps the original static, which throws. Use a lambda:\n  "
                        + String.join("\n  ", offenders));
    }

    /**
     * The three sites the outage was traced to, pinned by name. The sweep above
     * would catch them again, but only while its entity discovery keeps working;
     * this fails loudly and specifically if anyone reintroduces them.
     */
    @Test
    void theAgreementEnrichmentCacheUsesDirectCallsNotMethodReferences() throws IOException {
        Path service = Path.of("src/main/java/dk/trustworks/intranet/agreementservice/services/AgreementService.java");
        assertTrue(Files.exists(service), "expected " + service.toAbsolutePath());
        String code = stripCommentsAndStrings(Files.readString(service));

        for (String broken : List.of("AgreementType::findById",
                                     "RecruitmentCandidate::findById",
                                     "TemplateClauseEntity::findById")) {
            assertFalse(code.contains(broken),
                    broken + " binds to the un-enhanced PanacheEntityBase stub and 500s every employee "
                            + "who has an agreement — this is the 2026-09-06 outage. Use a lambda.");
        }
        for (String fixed : List.of("AgreementType.findById(",
                                    "RecruitmentCandidate.findById(",
                                    "TemplateClauseEntity.findById(")) {
            assertTrue(code.contains(fixed),
                    "expected a direct " + fixed + ") call site — that is the form Quarkus rewrites");
        }
    }

    // ---- discovery -----------------------------------------------------------

    private static Set<String> panacheStatics() {
        Set<String> names = new HashSet<>();
        for (Method m : PanacheEntityBase.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && !m.isSynthetic()) {
                names.add(m.getName());
            }
        }
        return names;
    }

    /**
     * A simple name counts as an entity if <em>any</em> class declaring that name
     * reaches a Panache base. Two classes in this backend share a simple name with
     * a Panache entity, so resolving first-wins would drop a real entity; over-
     * inclusion only risks a reviewable false positive, under-inclusion is a blind
     * spot that looks like a pass.
     */
    private static Set<String> panacheEntities(Iterable<String> sources) {
        Map<String, Set<String>> supers = new HashMap<>();
        for (String code : sources) {
            Matcher m = CLASS_DECL.matcher(code);
            while (m.find()) {
                String sup = m.group(2);
                Set<String> observed = supers.computeIfAbsent(m.group(1), k -> new HashSet<>());
                if (sup != null) observed.add(sup.substring(sup.lastIndexOf('.') + 1));
            }
        }
        Set<String> entities = new HashSet<>();
        for (String cls : supers.keySet()) {
            Deque<String> queue = new ArrayDeque<>(List.of(cls));
            Set<String> seen = new HashSet<>();
            while (!queue.isEmpty()) {
                String current = queue.poll();
                if (!seen.add(current)) continue;
                if (PANACHE_BASES.contains(current)) {
                    entities.add(cls);
                    break;
                }
                queue.addAll(supers.getOrDefault(current, Set.of()));
            }
        }
        return entities;
    }

    private static Map<Path, String> strippedSources() throws IOException {
        Map<Path, String> sources = new HashMap<>();
        for (Path root : SOURCE_ROOTS) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java")).toList()) {
                    sources.put(file, stripCommentsAndStrings(Files.readString(file)));
                }
            }
        }
        assertFalse(sources.isEmpty(), "found no Java sources under " + SOURCE_ROOTS
                + " — this test reads the source, so it must run from the module root");
        return sources;
    }

    /**
     * Comments and literal content removed, so prose describing the trap — this
     * file's own javadoc and its string literals included — does not read as
     * committing it. Text blocks are handled explicitly: the backend has some
     * 1600 of them, and a stripper that mistook {@code """} for two string
     * delimiters would desynchronise and read code as text for the rest of the
     * file, silently blinding this guard.
     */
    static String stripCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';

            if (c == '/' && next == '/') {
                while (i < n && source.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) i++;
                i = Math.min(i + 2, n);
                continue;
            }
            if (c == '"' && next == '"' && i + 2 < n && source.charAt(i + 2) == '"') {
                i += 3;
                while (i + 2 < n && !(source.charAt(i) == '"'
                        && source.charAt(i + 1) == '"'
                        && source.charAt(i + 2) == '"')) {
                    if (source.charAt(i) == '\\') i++;
                    i++;
                }
                i = Math.min(i + 3, n);
                out.append("\"\"");
                continue;
            }
            if (c == '"') {
                i++;
                while (i < n && source.charAt(i) != '"' && source.charAt(i) != '\n') {
                    if (source.charAt(i) == '\\') i++;
                    i++;
                }
                i = Math.min(i + 1, n);
                out.append("\"\"");
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n && source.charAt(i) != '\'' && source.charAt(i) != '\n') {
                    if (source.charAt(i) == '\\') i++;
                    i++;
                }
                i = Math.min(i + 1, n);
                out.append("''");
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
