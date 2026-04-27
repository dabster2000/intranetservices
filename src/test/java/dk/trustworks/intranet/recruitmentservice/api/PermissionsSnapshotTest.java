package dk.trustworks.intranet.recruitmentservice.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snapshot test for §8.2 of the Recruitment Slice 1 plan: every recruitment endpoint's
 * effective scope set (class-level @RolesAllowed merged with method-level overrides) must
 * match the JSON matrix in {@code src/test/resources/recruitment-permissions-matrix.json}.
 *
 * <p>Fails on drift in either direction (added/removed endpoints, changed scope sets) so a
 * code review is forced when authorization rules change. The matrix is the snapshot; if a
 * scope is intentionally changed, update the JSON and the diff makes the change reviewable.
 *
 * <p>Slice 3b extension: a single public endpoint exists for the Microsoft Graph webhook
 * ({@link GraphNotificationResource} carries {@link PermitAll}). The scanner treats
 * {@link PermitAll}-annotated methods as having a synthetic single-role
 * {@code "@PermitAll"} so the matrix can capture them, and an extra invariant guards against
 * accidentally widening this to a second public endpoint.
 *
 * <p>Implementation note: uses pure reflection over a fixed list of resource classes to avoid
 * pulling in {@code org.reflections} as a test dependency and to avoid a {@code @QuarkusTest}
 * Arc/DB context (this test exercises annotations only).
 */
class PermissionsSnapshotTest {

    private static final String PERMIT_ALL_TOKEN = "@PermitAll";

    private static final List<Class<?>> RECRUITMENT_RESOURCES = List.of(
            RecruitmentStatusResource.class,
            OpenRoleResource.class,
            CandidateResource.class,
            ApplicationResource.class,
            CandidateCvResource.class,
            OpenRoleAiResource.class,
            CandidateAiResource.class,
            AiArtifactResource.class,
            InterviewResource.class,
            ScorecardResource.class,
            InterviewAiResource.class,
            IntegrationStatusResource.class,
            IntegrationRetryResource.class,
            GraphNotificationResource.class
    );

    @Test
    void everyRecruitmentEndpointMatchesMatrixSnapshot() throws Exception {
        var mapper = new ObjectMapper();
        var matrix = mapper.readTree(getClass().getResourceAsStream("/recruitment-permissions-matrix.json"));

        Map<String, Set<String>> expected = new TreeMap<>();
        for (JsonNode entry : matrix.get("endpoints")) {
            String key = entry.get("method").asText() + " " + entry.get("path").asText();
            Set<String> roles = new TreeSet<>();
            entry.get("rolesAny").forEach(r -> roles.add(r.asText()));
            expected.put(key, roles);
        }

        Map<String, Set<String>> actual = scanRecruitmentEndpoints();

        assertEquals(expected, actual,
                "Recruitment endpoint scope matrix has drifted from snapshot. " +
                        "Update src/test/resources/recruitment-permissions-matrix.json or restore @RolesAllowed.");
    }

    private Map<String, Set<String>> scanRecruitmentEndpoints() {
        Map<String, Set<String>> out = new TreeMap<>();
        for (Class<?> cls : RECRUITMENT_RESOURCES) {
            Path classPath = cls.getAnnotation(Path.class);
            if (classPath == null) {
                throw new IllegalStateException(cls.getName() + " is missing @Path");
            }
            String basePath = classPath.value();
            String classScope = roles(cls.getAnnotation(RolesAllowed.class));
            boolean classPermitAll = cls.isAnnotationPresent(PermitAll.class);
            for (Method m : cls.getDeclaredMethods()) {
                String method = httpMethod(m);
                if (method == null) continue;
                String subPath = m.isAnnotationPresent(Path.class) ? m.getAnnotation(Path.class).value() : "";
                String fullPath = basePath + (subPath.isEmpty() || subPath.startsWith("/") ? subPath : "/" + subPath);
                Set<String> effective = effectiveRoles(m, classScope, classPermitAll);
                out.put(method + " " + normalizePath(fullPath), effective);
            }
        }
        return out;
    }

    private static Set<String> effectiveRoles(Method m, String classScope, boolean classPermitAll) {
        // Method-level @PermitAll wins over @RolesAllowed (and over class-level).
        if (m.isAnnotationPresent(PermitAll.class)) {
            return Set.of(PERMIT_ALL_TOKEN);
        }
        String methodScope = roles(m.getAnnotation(RolesAllowed.class));
        if (methodScope != null) return splitCsv(methodScope);
        if (classScope != null) return splitCsv(classScope);
        if (classPermitAll) return Set.of(PERMIT_ALL_TOKEN);
        return Set.of();
    }

    private static String httpMethod(Method m) {
        if (m.isAnnotationPresent(GET.class)) return "GET";
        if (m.isAnnotationPresent(POST.class)) return "POST";
        if (m.isAnnotationPresent(PUT.class)) return "PUT";
        if (m.isAnnotationPresent(PATCH.class)) return "PATCH";
        if (m.isAnnotationPresent(DELETE.class)) return "DELETE";
        return null;
    }

    private static String roles(Annotation ann) {
        if (!(ann instanceof RolesAllowed ra)) return null;
        return String.join(",", ra.value());
    }

    private static Set<String> splitCsv(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String normalizePath(String p) {
        // Collapse any "//" introduced by stitching base + sub paths, and trim trailing slashes.
        String collapsed = p.replaceAll("/{2,}", "/");
        return collapsed.length() > 1 ? collapsed.replaceAll("/+$", "") : collapsed;
    }

    /** Sanity: ensures every resource class actually has at least one HTTP-mapped endpoint. */
    @Test
    void everyListedResourceHasAtLeastOneEndpoint() {
        for (Class<?> cls : RECRUITMENT_RESOURCES) {
            long count = Arrays.stream(cls.getDeclaredMethods())
                    .filter(m -> httpMethod(m) != null)
                    .count();
            assertTrue(count > 0, cls.getName() + " has no HTTP endpoints");
        }
    }

    /** Guard against unintentional duplicate path keys in the matrix file. */
    @Test
    void matrixHasNoDuplicateEndpointKeys() throws Exception {
        var mapper = new ObjectMapper();
        var matrix = mapper.readTree(getClass().getResourceAsStream("/recruitment-permissions-matrix.json"));
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode entry : matrix.get("endpoints")) {
            String key = entry.get("method").asText() + " " + entry.get("path").asText();
            assertTrue(seen.add(key), "Duplicate endpoint key in matrix: " + key);
        }
    }

    /**
     * Slice 3b invariant: exactly one recruitment endpoint may be marked
     * {@code publicEndpoint: true} (the Microsoft Graph webhook). Widening this
     * is a security-sensitive change and must be reviewed deliberately.
     */
    @Test
    void exactlyOneRecruitmentEndpointMayBePublic() throws Exception {
        var mapper = new ObjectMapper();
        var matrix = mapper.readTree(getClass().getResourceAsStream("/recruitment-permissions-matrix.json"));
        long publicCount = StreamSupport.stream(matrix.get("endpoints").spliterator(), false)
                .filter(entry -> {
                    JsonNode pub = entry.get("publicEndpoint");
                    return pub != null && pub.asBoolean(false);
                })
                .count();
        assertEquals(1L, publicCount,
                "exactly one recruitment endpoint may be publicEndpoint=true");
    }
}
