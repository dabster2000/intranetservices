package dk.trustworks.intranet.security.surface;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds a snapshot of the **externally-reachable** backend surface: what each endpoint
 * returns and accepts, field by field.
 *
 * <h2>Why this exists</h2>
 *
 * Phase 3's frontend manifest gates <em>who may call what</em>. Nothing gated <em>what
 * comes back</em>, and on the public surface that gap is already live: most {@code /public}
 * endpoints serialise persistence entities rather than DTOs, so
 * {@code GET /public/users/{uuid}/work} publishes {@code WorkFull.rate} and
 * {@code .discount} to every holder of a {@code public:read} key (findings F-21, F-22).
 * Phase 13 authors the facade that fixes that. Until it lands, this snapshot freezes
 * today's shape so the surface cannot widen by accident — a field added to an existing
 * DTO being the likelier leak than a wholesale type swap.
 *
 * <h2>Two categories, both external</h2>
 *
 * <ol>
 *   <li>{@code /public/**} — reachable with a {@code public:read} client key.</li>
 *   <li>{@code @PermitAll} — reachable with <strong>no credential at all</strong>, which is
 *       the higher-risk class even though it is the less obvious one.</li>
 * </ol>
 *
 * <h2>Why reflection and not a grep</h2>
 *
 * {@code grep -c '@PermitAll'} answers 27 across 9 files. Several of those are prose in a
 * Javadoc block and one — {@code UserResource.updatePasswordByUsername} — sits inside a
 * commented-out {@code /* … *&#47;} region. Reading the compiled classes counts what the
 * JVM will actually enforce, which is the only number that matters here.
 *
 * <h2>Determinism</h2>
 *
 * {@link Class#getDeclaredFields()} has no specified order, so every collection here is a
 * {@link TreeMap} or a sorted list and the JSON is written by hand rather than by an
 * {@code ObjectMapper} whose configuration could drift. Two runs over the same classes must
 * be byte-identical or the diff this whole mechanism relies on is worthless.
 */
public final class PublicSurfaceSnapshotter {

    /** How deep the field walk goes before recording a truncation. Entity graphs are cyclic. */
    static final int MAX_DEPTH = 4;

    /**
     * Field names that must never appear on an externally reachable response.
     * Matched case-insensitively as substrings, because {@code cprNumber},
     * {@code bankAccountNumber} and {@code salaryType} are all the same problem.
     */
    static final List<String> SENSITIVE_FRAGMENTS =
            List.of("cpr", "password", "bankaccount", "salary", "pension", "birthday", "privatephone");

    /** Packages whose types are recorded as leaves rather than walked. */
    private static final List<String> LEAF_PACKAGES =
            List.of("java.", "javax.", "jakarta.", "sun.", "com.sun.", "org.jboss.", "io.quarkus.", "io.smallrye.");

    /** JAX-RS parameter annotations — a parameter carrying one is not the request body. */
    private static final List<Class<? extends Annotation>> PARAM_ANNOTATIONS = List.of(
            PathParam.class, QueryParam.class, HeaderParam.class, CookieParam.class,
            FormParam.class, MatrixParam.class, BeanParam.class, Context.class);

    private PublicSurfaceSnapshotter() {
    }

    // -----------------------------------------------------------------------
    // Model
    // -----------------------------------------------------------------------

    /** One externally reachable endpoint. */
    public record Endpoint(
            String key,
            String resource,
            String access,
            String returns,
            boolean returnsEntity,
            String requestBody,
            boolean requestBodyIsEntity,
            Map<String, Map<String, String>> fields) {
    }

    public record Snapshot(
            Map<String, Endpoint> endpoints,
            List<String> unresolved,
            List<String> truncated,
            List<String> sensitiveFields) {
    }

    // -----------------------------------------------------------------------
    // Class discovery
    // -----------------------------------------------------------------------

    /**
     * Every application class on the compiled output directory, loaded without running its
     * static initialisers. Loading is what makes annotations readable; initialising would
     * run application code inside a unit test, which this must never do.
     */
    static List<Class<?>> applicationClasses() {
        URL location = PublicSurfaceSnapshotter.class.getProtectionDomain().getCodeSource().getLocation();
        // The test class lives in target/test-classes; the application lives next door.
        File testClasses = new File(location.getPath());
        File classes = new File(testClasses.getParentFile(), "classes");
        if (!classes.isDirectory()) {
            throw new IllegalStateException(
                    "Compiled classes not found at " + classes + " — run `mvn test-compile` first.");
        }

        List<String> names = new ArrayList<>();
        collectClassNames(classes, "", names);
        names.sort(Comparator.naturalOrder());

        ClassLoader loader = PublicSurfaceSnapshotter.class.getClassLoader();
        List<Class<?>> out = new ArrayList<>();
        for (String name : names) {
            if (!name.startsWith("dk.trustworks.")) continue;
            try {
                out.add(Class.forName(name, false, loader));
            } catch (Throwable ignored) {
                // A class whose supertypes are not on the test classpath cannot be a JAX-RS
                // resource that Quarkus boots, so skipping it cannot hide an endpoint.
            }
        }
        return out;
    }

    private static void collectClassNames(File dir, String prefix, List<String> acc) {
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            if (child.isDirectory()) {
                collectClassNames(child, prefix + child.getName() + ".", acc);
            } else if (child.getName().endsWith(".class") && !child.getName().contains("$$")) {
                acc.add(prefix + child.getName().substring(0, child.getName().length() - ".class".length()));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Endpoint discovery
    // -----------------------------------------------------------------------

    public static Snapshot build() {
        Map<String, Endpoint> endpoints = new TreeMap<>();
        Set<String> unresolved = new TreeSet<>();
        Set<String> truncated = new TreeSet<>();
        Set<String> sensitive = new TreeSet<>();

        for (Class<?> resource : applicationClasses()) {
            Path classPath = resource.getAnnotation(Path.class);
            boolean classPermitAll = resource.isAnnotationPresent(PermitAll.class);
            String classPrefix = classPath == null ? null : classPath.value();

            for (Method method : sortedMethods(resource)) {
                String verb = httpVerb(method);
                if (verb == null) continue;

                Path methodPath = method.getAnnotation(Path.class);
                String path = joinPaths(classPrefix, methodPath == null ? null : methodPath.value());
                if (path == null) continue;

                boolean permitAll = method.isAnnotationPresent(PermitAll.class)
                        || (classPermitAll && !method.isAnnotationPresent(RolesAllowed.class));
                boolean isPublicPath = path.equals("/public") || path.startsWith("/public/");
                if (!permitAll && !isPublicPath) continue;

                String key = verb + " " + path;
                Map<String, Map<String, String>> fields = new TreeMap<>();

                Type returnType = method.getGenericReturnType();
                walk(returnType, key, fields, unresolved, truncated, sensitive, 0, new LinkedHashSet<>());

                Parameter body = requestBodyParameter(method);
                String bodyType = body == null ? null : typeName(body.getParameterizedType());
                if (body != null) {
                    walk(body.getParameterizedType(), key, fields, unresolved, truncated, sensitive, 0,
                            new LinkedHashSet<>());
                }

                String access = permitAll ? "permit-all" : accessScopes(resource, method);

                Endpoint endpoint = new Endpoint(
                        key,
                        resource.getName() + "#" + method.getName(),
                        access,
                        typeName(returnType),
                        isEntity(rawType(returnType)) || isEntity(elementType(returnType)),
                        bodyType,
                        body != null && (isEntity(rawType(body.getParameterizedType()))
                                || isEntity(elementType(body.getParameterizedType()))),
                        fields);

                Endpoint existing = endpoints.get(key);
                if (existing != null) {
                    // Two methods mapped to the same verb+path is a real (if unlikely) defect;
                    // recording both keeps it visible instead of letting one win silently.
                    unresolved.add(key + " — mapped by both " + existing.resource() + " and " + endpoint.resource());
                }
                endpoints.put(key, endpoint);
            }
        }

        return new Snapshot(endpoints, List.copyOf(unresolved), List.copyOf(truncated), List.copyOf(sensitive));
    }

    private static List<Method> sortedMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>(Arrays.asList(type.getDeclaredMethods()));
        methods.sort(Comparator.comparing(Method::getName).thenComparing(Method::toGenericString));
        return methods;
    }

    private static String httpVerb(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            HttpMethod http = annotation.annotationType().getAnnotation(HttpMethod.class);
            if (http != null) return http.value();
        }
        return null;
    }

    private static String joinPaths(String classPath, String methodPath) {
        if (classPath == null && methodPath == null) return null;
        String combined = (classPath == null ? "" : classPath) + "/" + (methodPath == null ? "" : methodPath);
        combined = combined.replaceAll("/+", "/");
        if (combined.length() > 1 && combined.endsWith("/")) combined = combined.substring(0, combined.length() - 1);
        return combined.startsWith("/") ? combined : "/" + combined;
    }

    private static String accessScopes(Class<?> resource, Method method) {
        RolesAllowed onMethod = method.getAnnotation(RolesAllowed.class);
        RolesAllowed onClass = resource.getAnnotation(RolesAllowed.class);
        RolesAllowed effective = onMethod != null ? onMethod : onClass;
        if (effective == null) return "unannotated";
        List<String> values = new ArrayList<>(Arrays.asList(effective.value()));
        values.sort(Comparator.naturalOrder());
        return String.join(",", values);
    }

    private static Parameter requestBodyParameter(Method method) {
        for (Parameter parameter : method.getParameters()) {
            boolean isParam = PARAM_ANNOTATIONS.stream().anyMatch(parameter::isAnnotationPresent);
            if (!isParam) return parameter;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Type walking
    // -----------------------------------------------------------------------

    static String typeName(Type type) {
        if (type instanceof Class<?> c) return c.getName();
        if (type instanceof ParameterizedType p) {
            StringBuilder sb = new StringBuilder(typeName(p.getRawType()));
            sb.append('<');
            Type[] args = p.getActualTypeArguments();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(typeName(args[i]));
            }
            return sb.append('>').toString();
        }
        if (type instanceof WildcardType) return "?";
        if (type instanceof TypeVariable<?> v) return v.getName();
        return type.getTypeName();
    }

    private static Class<?> rawType(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType p) return rawType(p.getRawType());
        return null;
    }

    /** The element type of a `List<X>` / `Set<X>` / `X[]`, or null. */
    private static Class<?> elementType(Type type) {
        if (type instanceof ParameterizedType p) {
            Type[] args = p.getActualTypeArguments();
            if (args.length >= 1) return rawType(args[args.length - 1]);
        }
        if (type instanceof Class<?> c && c.isArray()) return c.getComponentType();
        return null;
    }

    static boolean isEntity(Class<?> type) {
        if (type == null) return false;
        if (type.isAnnotationPresent(Entity.class) || type.isAnnotationPresent(MappedSuperclass.class)) return true;
        for (Class<?> s = type.getSuperclass(); s != null && s != Object.class; s = s.getSuperclass()) {
            if (s.getName().startsWith("io.quarkus.hibernate.orm.panache")) return true;
            if (s.isAnnotationPresent(Entity.class) || s.isAnnotationPresent(MappedSuperclass.class)) return true;
        }
        return false;
    }

    private static boolean isLeaf(Class<?> type) {
        if (type == null) return true;
        if (type.isPrimitive() || type.isEnum() || type.isArray()) return true;
        for (String pkg : LEAF_PACKAGES) {
            if (type.getName().startsWith(pkg)) return true;
        }
        return false;
    }

    /**
     * Record every field of {@code type} (and of the types it references) that Jackson could
     * serialise, with the annotations that change what is published.
     *
     * <p>{@code @JsonIgnore} is <em>recorded</em>, not skipped: deleting one publishes the
     * field, and that deletion must appear as a snapshot diff rather than as nothing.
     */
    private static void walk(
            Type type,
            String endpointKey,
            Map<String, Map<String, String>> out,
            Set<String> unresolved,
            Set<String> truncated,
            Set<String> sensitive,
            int depth,
            Set<Class<?>> visiting) {

        if (type == null) return;

        if (type instanceof ParameterizedType p) {
            for (Type arg : p.getActualTypeArguments()) {
                walk(arg, endpointKey, out, unresolved, truncated, sensitive, depth, visiting);
            }
            Class<?> raw = rawType(p);
            if (raw != null && !isLeaf(raw)) {
                walkClass(raw, endpointKey, out, unresolved, truncated, sensitive, depth, visiting);
            }
            return;
        }

        Class<?> raw = rawType(type);
        if (raw == null) {
            unresolved.add(endpointKey + " — unresolvable type " + typeName(type));
            return;
        }
        if (raw.isArray()) {
            walk(raw.getComponentType(), endpointKey, out, unresolved, truncated, sensitive, depth, visiting);
            return;
        }
        if (raw == Object.class || raw.getName().equals("jakarta.ws.rs.core.Response")) {
            // A `Response` or a raw `Object` cannot be resolved statically. Recorded as a gap
            // so it is visible rather than absent (task 3.10, conservative on ambiguity).
            unresolved.add(endpointKey + " — returns " + raw.getSimpleName() + ", shape not statically known");
            return;
        }
        if (isLeaf(raw)) return;

        walkClass(raw, endpointKey, out, unresolved, truncated, sensitive, depth, visiting);
    }

    private static void walkClass(
            Class<?> type,
            String endpointKey,
            Map<String, Map<String, String>> out,
            Set<String> unresolved,
            Set<String> truncated,
            Set<String> sensitive,
            int depth,
            Set<Class<?>> visiting) {

        if (out.containsKey(type.getName()) || visiting.contains(type)) return;

        if (depth >= MAX_DEPTH) {
            truncated.add(endpointKey + " — " + type.getName() + " not expanded (depth cap " + MAX_DEPTH + ")");
            out.computeIfAbsent(type.getName(), k -> new TreeMap<>())
                    .put("__truncated__", "depth cap " + MAX_DEPTH + " reached; nested fields not recorded");
            return;
        }

        visiting.add(type);
        Map<String, String> fields = new TreeMap<>();
        out.put(type.getName(), fields);

        List<Type> nested = new ArrayList<>();

        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().startsWith("io.quarkus.")) break; // PanacheEntityBase plumbing
            List<Field> declared = new ArrayList<>(Arrays.asList(c.getDeclaredFields()));
            declared.sort(Comparator.comparing(Field::getName)); // getDeclaredFields() order is unspecified
            for (Field field : declared) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                String description = describe(field.getGenericType(), field.getAnnotations(),
                        Modifier.isTransient(field.getModifiers()));
                fields.put(field.getName(), description);
                if (isSensitive(field.getName())) {
                    sensitive.add(endpointKey + " · " + type.getName() + "#" + field.getName());
                }
                nested.add(field.getGenericType());
            }
        }

        // Getter-only properties: a value with no backing field is still serialised, and
        // `Employee.getUserStatus()` is exactly that shape.
        for (Method method : sortedMethods(type)) {
            if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) continue;
            if (method.getParameterCount() != 0 || method.getReturnType() == void.class) continue;
            String property = propertyName(method);
            if (property == null || fields.containsKey(property)) continue;
            if (method.getDeclaringClass() != type) continue;
            String description = describe(method.getGenericReturnType(), method.getAnnotations(), false) + " @getter";
            fields.put(property, description);
            if (isSensitive(property)) {
                sensitive.add(endpointKey + " · " + type.getName() + "#" + property + " (getter)");
            }
            nested.add(method.getGenericReturnType());
        }

        for (Type t : nested) {
            walk(t, endpointKey, out, unresolved, truncated, sensitive, depth + 1, visiting);
        }
        visiting.remove(type);
    }

    private static String propertyName(Method method) {
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3))) {
            return decapitalise(name.substring(3));
        }
        if (name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))
                && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
            return decapitalise(name.substring(2));
        }
        return null;
    }

    private static String decapitalise(String s) {
        if (s.length() > 1 && Character.isUpperCase(s.charAt(1))) return s; // URL -> URL
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** `"double"`, `"boolean @JsonIgnore"`, `"String @JsonProperty(name)"`. */
    private static String describe(Type type, Annotation[] annotations, boolean isTransient) {
        StringBuilder sb = new StringBuilder(typeName(type));
        List<String> markers = new ArrayList<>();
        if (isTransient) markers.add("transient");
        for (Annotation a : annotations) {
            String name = a.annotationType().getName();
            switch (name) {
                case "com.fasterxml.jackson.annotation.JsonIgnore" -> markers.add("@JsonIgnore");
                case "com.fasterxml.jackson.annotation.JsonProperty" -> markers.add("@JsonProperty");
                case "org.eclipse.microprofile.openapi.annotations.media.Schema" -> markers.add("@Schema");
                case "com.fasterxml.jackson.annotation.JsonIgnoreProperties" -> markers.add("@JsonIgnoreProperties");
                default -> {
                    // Not a serialisation-shaping annotation.
                }
            }
        }
        markers.sort(Comparator.naturalOrder());
        for (String marker : markers) sb.append(' ').append(marker);
        return sb.toString();
    }

    static boolean isSensitive(String fieldName) {
        String lower = fieldName.toLowerCase(Locale.ROOT);
        return SENSITIVE_FRAGMENTS.stream().anyMatch(lower::contains);
    }

    // -----------------------------------------------------------------------
    // Serialisation — hand-written so the byte layout cannot drift with a
    // Jackson configuration change somewhere else in the application.
    // -----------------------------------------------------------------------

    public static String toJson(Snapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"generatedFrom\": \"src/test/java/dk/trustworks/intranet/security/surface/PublicSurfaceSnapshotter.java\",\n");
        sb.append("  \"totals\": {\n");
        sb.append("    \"endpoints\": ").append(snapshot.endpoints().size()).append(",\n");
        sb.append("    \"permitAllEndpoints\": ")
                .append(snapshot.endpoints().values().stream().filter(e -> e.access().equals("permit-all")).count())
                .append(",\n");
        sb.append("    \"entityReturningEndpoints\": ")
                .append(snapshot.endpoints().values().stream().filter(Endpoint::returnsEntity).count()).append(",\n");
        sb.append("    \"entityRequestBodies\": ")
                .append(snapshot.endpoints().values().stream().filter(Endpoint::requestBodyIsEntity).count())
                .append(",\n");
        sb.append("    \"unresolved\": ").append(snapshot.unresolved().size()).append(",\n");
        sb.append("    \"truncated\": ").append(snapshot.truncated().size()).append(",\n");
        sb.append("    \"sensitiveFields\": ").append(snapshot.sensitiveFields().size()).append('\n');
        sb.append("  },\n");

        sb.append("  \"endpoints\": {\n");
        List<String> keys = new ArrayList<>(snapshot.endpoints().keySet());
        for (int i = 0; i < keys.size(); i++) {
            Endpoint e = snapshot.endpoints().get(keys.get(i));
            sb.append("    ").append(quote(e.key())).append(": {\n");
            sb.append("      \"resource\": ").append(quote(e.resource())).append(",\n");
            sb.append("      \"access\": ").append(quote(e.access())).append(",\n");
            sb.append("      \"returns\": ").append(quote(e.returns())).append(",\n");
            sb.append("      \"returnsEntity\": ").append(e.returnsEntity()).append(",\n");
            sb.append("      \"requestBody\": ")
                    .append(e.requestBody() == null ? "null" : quote(e.requestBody())).append(",\n");
            sb.append("      \"requestBodyIsEntity\": ").append(e.requestBodyIsEntity()).append(",\n");
            sb.append("      \"fields\": {\n");
            List<String> types = new ArrayList<>(e.fields().keySet());
            for (int t = 0; t < types.size(); t++) {
                Map<String, String> members = e.fields().get(types.get(t));
                sb.append("        ").append(quote(types.get(t))).append(": {\n");
                List<String> names = new ArrayList<>(members.keySet());
                for (int n = 0; n < names.size(); n++) {
                    sb.append("          ").append(quote(names.get(n))).append(": ")
                            .append(quote(members.get(names.get(n))));
                    sb.append(n == names.size() - 1 ? "\n" : ",\n");
                }
                sb.append("        }").append(t == types.size() - 1 ? "\n" : ",\n");
            }
            sb.append("      }\n");
            sb.append("    }").append(i == keys.size() - 1 ? "\n" : ",\n");
        }
        sb.append("  },\n");

        appendArray(sb, "unresolved", snapshot.unresolved(), false);
        appendArray(sb, "truncated", snapshot.truncated(), false);
        appendArray(sb, "sensitiveFields", snapshot.sensitiveFields(), true);
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendArray(StringBuilder sb, String name, List<String> values, boolean last) {
        sb.append("  ").append(quote(name)).append(": [");
        if (values.isEmpty()) {
            sb.append(']');
        } else {
            sb.append('\n');
            for (int i = 0; i < values.size(); i++) {
                sb.append("    ").append(quote(values.get(i))).append(i == values.size() - 1 ? "\n" : ",\n");
            }
            sb.append("  ]");
        }
        sb.append(last ? "\n" : ",\n");
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    // -----------------------------------------------------------------------
    // Human-readable enumeration (task 3.9) — the same data, for review
    // -----------------------------------------------------------------------

    public static String toMarkdown(Snapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Externally reachable backend surface\n\n");
        sb.append("<!-- GENERATED by PublicSurfaceSnapshotter — do not edit by hand.\n");
        sb.append("     Regenerate: ./mvnw -Dtest=PublicSurfaceSnapshotTest -Dpublic.surface.update=true test -->\n\n");
        sb.append("Two categories are listed, both reachable from outside the intranet:\n\n");
        sb.append("- **`public:read`** — the `/public/**` surface, reachable with a client key.\n");
        sb.append("- **`permit-all`** — reachable with **no credential at all**.\n\n");
        sb.append("`entity` marks an endpoint that serialises a persistence entity rather than a DTO.\n");
        sb.append("That count is Phase 13's burn-down target (findings F-21).\n\n");

        long permitAll = snapshot.endpoints().values().stream().filter(e -> e.access().equals("permit-all")).count();
        long entities = snapshot.endpoints().values().stream().filter(Endpoint::returnsEntity).count();
        sb.append("| | Count |\n|---|---|\n");
        sb.append("| Endpoints | ").append(snapshot.endpoints().size()).append(" |\n");
        sb.append("| … reachable with no credential (`@PermitAll`) | ").append(permitAll).append(" |\n");
        sb.append("| … returning a persistence entity | ").append(entities).append(" |\n");
        sb.append("| … accepting a persistence entity as request body | ")
                .append(snapshot.endpoints().values().stream().filter(Endpoint::requestBodyIsEntity).count())
                .append(" |\n");
        sb.append("| Sensitive-named fields on the surface | ").append(snapshot.sensitiveFields().size()).append(" |\n");
        sb.append("| Unresolved response shapes | ").append(snapshot.unresolved().size()).append(" |\n\n");

        sb.append("## Endpoints\n\n");
        sb.append("| Endpoint | Access | Returns | Entity | Request body | Resource |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (Endpoint e : snapshot.endpoints().values()) {
            sb.append("| `").append(e.key()).append("` | ").append(e.access())
                    .append(" | `").append(shortName(e.returns())).append("` | ")
                    .append(e.returnsEntity() ? "**yes**" : "no").append(" | ")
                    .append(e.requestBody() == null ? "—" : "`" + shortName(e.requestBody()) + "`"
                            + (e.requestBodyIsEntity() ? " **entity**" : ""))
                    .append(" | `").append(shortName(e.resource())).append("` |\n");
        }

        if (!snapshot.sensitiveFields().isEmpty()) {
            sb.append("\n## Sensitive-named fields already on the surface\n\n");
            sb.append("Grandfathered: frozen, not accepted. A *new* one fails the build.\n\n");
            for (String s : snapshot.sensitiveFields()) sb.append("- `").append(s).append("`\n");
        }
        if (!snapshot.unresolved().isEmpty()) {
            sb.append("\n## Unresolved shapes\n\n");
            sb.append("Recorded as gaps rather than omitted — the snapshot cannot vouch for these.\n\n");
            for (String s : snapshot.unresolved()) sb.append("- ").append(s).append('\n');
        }
        if (!snapshot.truncated().isEmpty()) {
            sb.append("\n## Truncated walks (depth cap ").append(MAX_DEPTH).append(")\n\n");
            for (String s : snapshot.truncated()) sb.append("- ").append(s).append('\n');
        }
        return sb.toString();
    }

    private static String shortName(String fqcn) {
        return fqcn.replaceAll("dk\\.trustworks\\.intranet\\.[a-z0-9.]*", "")
                .replaceAll("java\\.util\\.", "")
                .replaceAll("java\\.lang\\.", "");
    }

    // -----------------------------------------------------------------------
    // Files
    // -----------------------------------------------------------------------

    public static java.nio.file.Path snapshotFile() {
        return Paths.get("src", "test", "resources", "public-surface-snapshot.json").toAbsolutePath();
    }

    public static java.nio.file.Path markdownFile() {
        return Paths.get("docs", "access", "backend-public-surface.md").toAbsolutePath();
    }

    public static String read(java.nio.file.Path file) {
        try {
            return Files.exists(file) ? Files.readString(file) : null;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + file, e);
        }
    }

    public static void write(java.nio.file.Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write " + file, e);
        }
    }
}
