package dk.trustworks.intranet.security;

import dk.trustworks.intranet.domain.user.entity.RoleDefinition;
import dk.trustworks.intranet.security.RolePermissionAdminService.ProtectedPermissionException;
import dk.trustworks.intranet.security.RolePermissionAdminService.UnknownPermissionException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Read models and the binding write path for the Phase 7 admin console
 * (role → permission editor, effective permissions both directions, audit log
 * viewer, quarterly access review export).
 *
 * <p><strong>Every answer is derived from the running system, never from a
 * document</strong> — the phase's governing principle. All reads are native SQL over
 * the live catalogue tables; nothing here is hand-maintained.
 *
 * <p>The audit log is append-only: this resource exposes GET only, and no update or
 * delete path exists anywhere in the codebase (task 7.8).
 */
@Tag(name = "authz-admin", description = "Authorization catalogue administration (Phase 7)")
@Path("/authz")
@RequestScoped
@RolesAllowed({"admin:read"})
@SecurityRequirement(name = "jwt")
@Produces(MediaType.APPLICATION_JSON)
@JBossLog
public class AuthzAdminResource {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Inject
    EntityManager em;

    @Inject
    RolePermissionAdminService rolePermissionAdminService;

    @Inject
    AuthorizationService authorizationService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    // ------------------------------------------------------------------
    // Matrix — the editor's read model (7.3)
    // ------------------------------------------------------------------

    /**
     * The full editor read model: catalogue (with protection flags), roles (with
     * holder counts, so the blast radius of a change is visible before making it),
     * and every active binding.
     */
    @GET
    @Path("/matrix")
    @Operation(summary = "Permission catalogue, roles with holder counts, and active bindings")
    public Response matrix() {
        List<Map<String, Object>> permissions = new ArrayList<>();
        for (Permissions.Permission p : Permissions.CATALOGUE) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", p.key());
            entry.put("displayName", p.displayName());
            entry.put("category", p.category());
            entry.put("isProtected", ProtectedPermissions.isProtected(p.key()));
            permissions.add(entry);
        }

        Map<String, Long> holderCounts = holderCountsByRole();
        List<Map<String, Object>> roles = new ArrayList<>();
        for (RoleDefinition rd : RoleDefinition.listAllOrdered()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", rd.getName());
            entry.put("displayLabel", rd.getDisplayLabel());
            entry.put("isSystem", rd.isSystem());
            entry.put("holderCount", holderCounts.getOrDefault(rd.getName(), 0L));
            roles.add(entry);
        }

        List<Map<String, Object>> bindings = new ArrayList<>();
        for (Object[] row : this.<Object[]>nativeRows("""
                SELECT rp.role, rp.permission_key, rp.granted_by, rp.granted_at, rp.data_scope
                FROM role_permission rp
                JOIN permission p ON p.permission_key = rp.permission_key AND p.revoked_at IS NULL
                WHERE rp.revoked_at IS NULL
                ORDER BY rp.role, rp.permission_key
                """)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", row[0]);
            entry.put("permissionKey", row[1]);
            entry.put("grantedBy", row[2]);
            entry.put("grantedAt", String.valueOf(row[3]));
            entry.put("dataScope", row[4]);
            bindings.add(entry);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("permissions", permissions);
        payload.put("roles", roles);
        payload.put("bindings", bindings);
        payload.put("protectedPrefixes", ProtectedPermissions.PROTECTED_PREFIXES);
        payload.put("dataScopes", java.util.Arrays.stream(DataScope.values()).map(Enum::name).toList());
        return Response.ok(payload).build();
    }

    // ------------------------------------------------------------------
    // Binding writes (7.3 + 7.9)
    // ------------------------------------------------------------------

    @POST
    @Path("/roles/{role}/permissions/{permissionKey}")
    @RolesAllowed({"admin:write"})
    @Operation(summary = "Grant a permission to a role (audited, version-bumped)")
    public Response grant(@PathParam("role") String role,
                          @PathParam("permissionKey") String permissionKey) {
        try {
            rolePermissionAdminService.grant(role, permissionKey);
            return Response.ok(Map.of("role", role.toUpperCase(), "permissionKey",
                    permissionKey.toLowerCase(), "active", true)).build();
        } catch (ProtectedPermissionException e) {
            return conflict(e.getMessage());
        } catch (UnknownPermissionException e) {
            return badRequest(e.getMessage());
        } catch (NotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @DELETE
    @Path("/roles/{role}/permissions/{permissionKey}")
    @RolesAllowed({"admin:write"})
    @Operation(summary = "Revoke a permission from a role (tombstone; audited, version-bumped)")
    public Response revoke(@PathParam("role") String role,
                           @PathParam("permissionKey") String permissionKey) {
        try {
            rolePermissionAdminService.revoke(role, permissionKey);
            return Response.ok(Map.of("role", role.toUpperCase(), "permissionKey",
                    permissionKey.toLowerCase(), "active", false)).build();
        } catch (ProtectedPermissionException e) {
            return conflict(e.getMessage());
        } catch (UnknownPermissionException e) {
            return badRequest(e.getMessage());
        } catch (NotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    /** Body of a scope change: one of the {@link DataScope} names. */
    public record ScopeChangeRequest(String scope) {}

    @PUT
    @Path("/roles/{role}/permissions/{permissionKey}/scope")
    @RolesAllowed({"admin:write"})
    @Operation(summary = "Change a binding's data scope (Phase 8; protected permissions refused, audited, version-bumped)")
    public Response changeScope(@PathParam("role") String role,
                                @PathParam("permissionKey") String permissionKey,
                                ScopeChangeRequest body) {
        DataScope scope = body == null ? null : DataScope.fromDb(body.scope());
        if (scope == null) {
            return badRequest("Unknown data scope — expected one of "
                    + java.util.Arrays.toString(DataScope.values()));
        }
        try {
            RolePermission binding = rolePermissionAdminService.changeScope(role, permissionKey, scope);
            return Response.ok(Map.of("role", binding.getRole(), "permissionKey",
                    binding.getPermissionKey(), "dataScope", binding.getDataScope().name())).build();
        } catch (ProtectedPermissionException e) {
            return conflict(e.getMessage());
        } catch (UnknownPermissionException e) {
            return badRequest(e.getMessage());
        } catch (NotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Authorization decisions (Phase 8, task 8.4) — the BFF's thin call
    // ------------------------------------------------------------------

    /**
     * Body of a subject-access check. {@code allowSelf} / {@code allowTeamLead}
     * carry the retired BFF guard's tier options: {@code allowSelf=false}
     * excludes the OWN tier (mutations — self-service is read-only);
     * {@code allowTeamLead=false} excludes every relationship tier (surfaces
     * where team-lead reach would be escalation — HR/ADMIN only).
     */
    public record AccessCheckRequest(String permissionKey, String subjectUuid,
                                     Boolean allowSelf, Boolean allowTeamLead) {}

    /**
     * Decides whether the acting human — always the {@code X-Requested-By}
     * header, never a body field, so a confused BFF route cannot ask about
     * someone else — may touch one subject under a permission. Consumed by the
     * BFF's {@code checkEmployeeDataAccess} (Phase 8, task 8.5).
     */
    @POST
    @Path("/check")
    @Operation(summary = "Row-level access decision for the acting user (X-Requested-By)")
    public Response check(AccessCheckRequest body) {
        if (body == null || !notBlank(body.permissionKey()) || !notBlank(body.subjectUuid())) {
            return badRequest("permissionKey and subjectUuid are required");
        }
        String actor = actorOrNull();
        if (actor == null) {
            return badRequest("No acting user — the X-Requested-By header is required for access checks");
        }
        java.util.EnumSet<DataScope> disabled = java.util.EnumSet.noneOf(DataScope.class);
        if (Boolean.FALSE.equals(body.allowSelf())) {
            disabled.add(DataScope.OWN);
        }
        if (Boolean.FALSE.equals(body.allowTeamLead())) {
            disabled.add(DataScope.TEAM);
            disabled.add(DataScope.PRACTICE);
            disabled.add(DataScope.COMPANY);
        }
        AuthorizationService.AccessDecision decision = authorizationService.decideSubjectAccess(
                actor, body.permissionKey().trim().toLowerCase(), body.subjectUuid().trim(),
                LocalDate.now(), disabled);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("allowed", decision.allowed());
        payload.put("actorUuid", actor);
        payload.put("widestScope", decision.widestScope() == null ? null : decision.widestScope().name());
        payload.put("unbounded", decision.unbounded());
        payload.put("subjectCount", decision.subjectCount());
        payload.put("reason", decision.reason());
        return Response.ok(payload).build();
    }

    /**
     * A user's reach for one permission — the simulator's trace data
     * ("scope TEAM, 12 subjects as of 2026-08-03"). Subject UUIDs are counted,
     * never listed: the console needs the size, not a person inventory.
     */
    @GET
    @Path("/users/{useruuid}/reach/{permissionKey}")
    @Operation(summary = "A user's resolved data-scope reach for a permission (simulator trace)")
    public Response reach(@PathParam("useruuid") String useruuid,
                          @PathParam("permissionKey") String permissionKey,
                          @QueryParam("asOf") String asOf,
                          @QueryParam("subject") String subject) {
        String key = permissionKey.trim().toLowerCase();
        if (Permissions.byKey(key) == null) {
            return badRequest("Unknown permission: " + key);
        }
        LocalDate asOfDate;
        try {
            asOfDate = notBlank(asOf) ? LocalDate.parse(asOf.trim()) : LocalDate.now();
        } catch (java.time.format.DateTimeParseException e) {
            return badRequest("Invalid asOf — use ISO date (2026-08-06)");
        }
        ScopeResolution reach = authorizationService.resolveReach(
                useruuid, key, asOfDate, java.util.Set.of());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("useruuid", useruuid);
        payload.put("permissionKey", key);
        payload.put("asOf", asOfDate.toString());
        payload.put("widestScope", reach.widestScope() == null ? null : reach.widestScope().name());
        payload.put("unbounded", reach.unbounded());
        payload.put("subjectCount", reach.unbounded() ? null : reach.subjects().size());
        // "Bo is not among them" — membership of one subject, never the list.
        payload.put("subjectIncluded", notBlank(subject) ? reach.permits(subject.trim()) : null);
        return Response.ok(payload).build();
    }

    private String actorOrNull() {
        try {
            String actor = requestHeaderHolder.getUserUuid();
            return (actor == null || actor.isBlank()) ? null : actor;
        } catch (jakarta.enterprise.context.ContextNotActiveException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Effective permissions, both directions (7.5)
    // ------------------------------------------------------------------

    /**
     * Reverse lookup: everyone who holds a permission, as <em>people</em>, with the
     * role(s) that grant it. "Who can read salaries?" must return names.
     */
    @GET
    @Path("/permissions/{permissionKey}/holders")
    @Operation(summary = "Everyone holding a permission, as people, with granting roles")
    public Response holders(@PathParam("permissionKey") String permissionKey) {
        String key = permissionKey.trim().toLowerCase();
        if (Permissions.byKey(key) == null) {
            return badRequest("Unknown permission: " + key);
        }

        // LinkedHashMap keyed by user uuid: one entry per person, roles aggregated.
        Map<String, Map<String, Object>> byUser = new LinkedHashMap<>();
        for (Object[] row : this.<Object[]>nativeRows("""
                SELECT u.uuid, u.firstname, u.lastname, u.username, r.role
                FROM role_permission rp
                JOIN roles r ON r.role = rp.role
                JOIN user u ON u.uuid = r.useruuid
                JOIN permission p ON p.permission_key = rp.permission_key AND p.revoked_at IS NULL
                WHERE rp.permission_key = :key AND rp.revoked_at IS NULL
                ORDER BY u.lastname, u.firstname, r.role
                """, q -> q.setParameter("key", key))) {
            String uuid = (String) row[0];
            Map<String, Object> person = byUser.computeIfAbsent(uuid, id -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("useruuid", id);
                entry.put("firstname", row[1]);
                entry.put("lastname", row[2]);
                entry.put("username", row[3]);
                entry.put("viaRoles", new ArrayList<String>());
                return entry;
            });
            @SuppressWarnings("unchecked")
            List<String> viaRoles = (List<String>) person.get("viaRoles");
            viaRoles.add((String) row[4]);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("permissionKey", key);
        payload.put("holders", new ArrayList<>(byUser.values()));
        payload.put("holderCount", byUser.size());
        return Response.ok(payload).build();
    }

    /**
     * Forward lookup with provenance: everything one person can do, and via which
     * role(s). The plain key list ({@code GET /users/{uuid}/permissions}) stays the
     * BFF's decision endpoint; this adds the trace for the console.
     */
    @GET
    @Path("/users/{useruuid}/permissions/trace")
    @Operation(summary = "A user's effective permissions with the granting role(s) and data scope(s) per key")
    public Response trace(@PathParam("useruuid") String useruuid) {
        Map<String, List<String>> viaByKey = new TreeMap<>();
        Map<String, List<String>> scopesByKey = new TreeMap<>();
        for (Object[] row : this.<Object[]>nativeRows("""
                SELECT rp.permission_key, r.role, rp.data_scope
                FROM roles r
                JOIN role_permission rp ON rp.role = r.role AND rp.revoked_at IS NULL
                JOIN permission p ON p.permission_key = rp.permission_key AND p.revoked_at IS NULL
                WHERE r.useruuid = :useruuid
                ORDER BY rp.permission_key, r.role
                """, q -> q.setParameter("useruuid", useruuid))) {
            viaByKey.computeIfAbsent((String) row[0], k -> new ArrayList<>()).add((String) row[1]);
            List<String> scopes = scopesByKey.computeIfAbsent((String) row[0], k -> new ArrayList<>());
            if (!scopes.contains((String) row[2])) {
                scopes.add((String) row[2]);
            }
        }

        List<String> roles = this.nativeRows(
                "SELECT role FROM roles WHERE useruuid = :useruuid ORDER BY role",
                q -> q.setParameter("useruuid", useruuid));

        List<Map<String, Object>> permissions = new ArrayList<>();
        viaByKey.forEach((key, viaRoles) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("permissionKey", key);
            entry.put("viaRoles", viaRoles);
            // Phase 8: every granted scope for the key. Boolean consumers are
            // satisfied only when this contains ALL (the legacy projection).
            entry.put("scopes", scopesByKey.getOrDefault(key, List.of()));
            permissions.add(entry);
        });

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("useruuid", useruuid);
        payload.put("roles", roles);
        payload.put("permissions", permissions);
        return Response.ok(payload).build();
    }

    // ------------------------------------------------------------------
    // Audit log viewer (7.8) — GET only, append-only by construction
    // ------------------------------------------------------------------

    @GET
    @Path("/audit")
    @Operation(summary = "Authorization audit trail, filterable; append-only (no edit or delete exists)")
    public Response audit(@QueryParam("actor") String actor,
                          @QueryParam("action") String action,
                          @QueryParam("targetType") String targetType,
                          @QueryParam("targetId") String targetId,
                          @QueryParam("from") String from,
                          @QueryParam("to") String to,
                          @DefaultValue("100") @QueryParam("limit") int limit,
                          @DefaultValue("0") @QueryParam("offset") int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, actor_uuid, action, target_type, target_id, before_json, after_json, at
                FROM authz_audit WHERE 1=1
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        if (notBlank(actor)) {
            sql.append(" AND actor_uuid = :actor");
            params.put("actor", actor.trim());
        }
        if (notBlank(action)) {
            sql.append(" AND action = :action");
            params.put("action", action.trim());
        }
        if (notBlank(targetType)) {
            sql.append(" AND target_type = :targetType");
            params.put("targetType", targetType.trim());
        }
        if (notBlank(targetId)) {
            sql.append(" AND target_id = :targetId");
            params.put("targetId", targetId.trim());
        }
        try {
            if (notBlank(from)) {
                sql.append(" AND at >= :fromTs");
                params.put("fromTs", parseInstant(from, false));
            }
            if (notBlank(to)) {
                sql.append(" AND at <= :toTs");
                params.put("toTs", parseInstant(to, true));
            }
        } catch (java.time.format.DateTimeParseException e) {
            return badRequest("Invalid date filter — use ISO date (2026-08-06) or date-time");
        }
        sql.append(" ORDER BY at DESC, id DESC");
        int cappedLimit = Math.min(Math.max(limit, 1), 500);
        int safeOffset = Math.max(offset, 0);

        List<Object[]> rows = this.nativeRows(sql.toString(), q -> {
            params.forEach(q::setParameter);
            q.setMaxResults(cappedLimit);
            q.setFirstResult(safeOffset);
            return q;
        });

        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", ((Number) row[0]).longValue());
            entry.put("actorUuid", row[1]);
            entry.put("action", row[2]);
            entry.put("targetType", row[3]);
            entry.put("targetId", row[4]);
            entry.put("before", row[5]);
            entry.put("after", row[6]);
            entry.put("at", String.valueOf(row[7]));
            entries.add(entry);
        }
        return Response.ok(Map.of("entries", entries)).build();
    }

    // ------------------------------------------------------------------
    // Quarterly access review export (7.10)
    // ------------------------------------------------------------------

    /**
     * Dated export of who holds what, per role and per permission, for business-owner
     * sign-off. Generated from the live system at request time, never from a document.
     */
    @GET
    @Path("/export")
    @Operation(summary = "Access review export: who holds what, per role and per permission")
    public Response export() {
        // role → holders (people)
        Map<String, List<Map<String, Object>>> holdersByRole = new TreeMap<>();
        for (Object[] row : this.<Object[]>nativeRows("""
                SELECT r.role, u.uuid, u.firstname, u.lastname, u.username
                FROM roles r
                JOIN user u ON u.uuid = r.useruuid
                ORDER BY r.role, u.lastname, u.firstname
                """)) {
            Map<String, Object> person = new LinkedHashMap<>();
            person.put("useruuid", row[1]);
            person.put("firstname", row[2]);
            person.put("lastname", row[3]);
            person.put("username", row[4]);
            holdersByRole.computeIfAbsent((String) row[0], k -> new ArrayList<>()).add(person);
        }

        // permission → granting roles (active, non-revoked)
        Map<String, List<String>> rolesByPermission = new TreeMap<>();
        for (Object[] row : this.<Object[]>nativeRows("""
                SELECT rp.permission_key, rp.role
                FROM role_permission rp
                JOIN permission p ON p.permission_key = rp.permission_key AND p.revoked_at IS NULL
                WHERE rp.revoked_at IS NULL
                ORDER BY rp.permission_key, rp.role
                """)) {
            rolesByPermission.computeIfAbsent((String) row[0], k -> new ArrayList<>()).add((String) row[1]);
        }

        List<Map<String, Object>> roles = new ArrayList<>();
        holdersByRole.forEach((role, holders) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", role);
            entry.put("holderCount", holders.size());
            entry.put("holders", holders);
            roles.add(entry);
        });

        List<Map<String, Object>> permissions = new ArrayList<>();
        rolesByPermission.forEach((key, grantingRoles) -> {
            // Distinct people across all granting roles.
            Map<String, Map<String, Object>> people = new LinkedHashMap<>();
            for (String role : grantingRoles) {
                for (Map<String, Object> person : holdersByRole.getOrDefault(role, List.of())) {
                    people.putIfAbsent((String) person.get("useruuid"), person);
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            Permissions.Permission cataloguePermission = Permissions.byKey(key);
            entry.put("permissionKey", key);
            entry.put("displayName", cataloguePermission != null ? cataloguePermission.displayName() : key);
            entry.put("category", cataloguePermission != null ? cataloguePermission.category() : null);
            entry.put("isProtected", ProtectedPermissions.isProtected(key));
            entry.put("roles", grantingRoles);
            entry.put("holderCount", people.size());
            entry.put("holders", new ArrayList<>(people.values()));
            permissions.add(entry);
        });

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", LocalDateTime.now().format(ISO));
        payload.put("source", "live role_permission / roles / user tables — generated, never hand-maintained");
        payload.put("roles", roles);
        payload.put("permissions", permissions);
        return Response.ok(payload).build();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, Long> holderCountsByRole() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : this.<Object[]>nativeRows(
                "SELECT role, COUNT(DISTINCT useruuid) FROM roles GROUP BY role")) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private interface QueryCustomizer {
        jakarta.persistence.Query apply(jakarta.persistence.Query query);
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> nativeRows(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> nativeRows(String sql, QueryCustomizer customizer) {
        return customizer.apply(em.createNativeQuery(sql)).getResultList();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Accepts ISO date or date-time; a bare date becomes start/end of that day. */
    private static LocalDateTime parseInstant(String value, boolean endOfDay) {
        String v = value.trim();
        if (v.length() == 10) {
            LocalDate date = LocalDate.parse(v);
            return endOfDay ? date.atTime(23, 59, 59) : date.atStartOfDay();
        }
        return LocalDateTime.parse(v);
    }

    private static Response conflict(String message) {
        return Response.status(Response.Status.CONFLICT).entity(Map.of("error", message)).build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", message)).build();
    }

    private static Response notFound(String message) {
        return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", message)).build();
    }
}
