# Validation Report — API Auth Backend: Audit Log + Endpoint Registry

**Date:** 2026-03-13
**Validator:** qa-validator
**Scope:** AuditLogResource, EndpointRegistryResource, EndpointRegistryService, ApiClientRepository (audit methods), DTOs

---

## Summary: PARTIAL PASS (1 critical bug, 3 minor findings)

---

## 1. Compile Check

**PASSED.** `./mvnw compile` exits cleanly. Only JVM JDK 21 restricted-access warnings (unrelated to this change).

---

## 2. Code Review

### 2.1 AuditLogResource

| Check | Result |
|---|---|
| `@Path("/auth/audit-log")` | PASS — line 22 |
| `@RolesAllowed({"admin:*", "ADMIN", "SYSTEM"})` | PASS — line 24. Note: spec REQ-BE-04 only requires `admin:*` + `ADMIN`; `SYSTEM` is an addition (acceptable — more permissive than spec) |
| GET method with all query params (`client_uuid`, `event_type`, `from`, `to`, `page`, `size`) | PASS — lines 35–40 |
| `size` capped at 200 | PASS — `MAX_PAGE_SIZE = 200`, `Math.min(Math.max(size, 1), MAX_PAGE_SIZE)` line 43 |
| Sort by `created_at DESC` | PASS — `ORDER BY a.createdAt DESC` in `buildAuditLogJpql` line 178 |
| `client_id` resolved (not just UUID) in response | PASS — LEFT JOIN on ApiClient, `c.clientId` returned as row[2], mapped to `AuditLogEntryResponse.clientId` |
| Parameterized JPQL queries — no string concatenation for user values | PASS — all user-supplied values (`clientUuid`, `eventTypes`, `from`, `to`) bound via named parameters `:clientUuid`, `:eventTypes`, `:fromDate`, `:toDate`. The JPQL string itself is only built with static string fragments + parameter placeholders. |
| Input validation on `event_type` | PASS — `AuditEventType::valueOf` with `BadRequestException` fallback |
| Input validation on `from`/`to` | PASS — `LocalDateTime.parse()` with `BadRequestException` fallback |
| Response envelope: `items`, `totalCount`, `page`, `size` | PASS — `AuditLogPageResponse` record has all four fields. Field names: `items` (List), `totalCount` (with `@JsonProperty("totalCount")`), `page`, `size`. Matches REQ-BE-03. |

#### CRITICAL BUG — JPQL `LEFT JOIN ... ON` with no mapped association (ApiClientRepository lines 157–160)

The JPQL queries use:

```java
"SELECT COUNT(a) FROM ApiClientAuditLog a LEFT JOIN ApiClient c ON a.clientUuid = c.uuid"
"FROM ApiClientAuditLog a LEFT JOIN ApiClient c ON a.clientUuid = c.uuid"
```

**This is invalid JPQL.** JPQL `JOIN ... ON` (ad-hoc join) was introduced in JPA 2.1 and IS supported by Hibernate 6 (which Quarkus 3.30.x ships with). However, the `LEFT JOIN ApiClient c ON a.clientUuid = c.uuid` join joins on a field comparison between a `String` column in `ApiClientAuditLog` (`a.clientUuid`) and the `@Id` field of `ApiClient` (`c.uuid`). This is a **cross-entity join on non-FK fields**.

Hibernate 6 supports this syntax (`LEFT JOIN <Entity> <alias> ON <condition>`) only when both sides reference persistent entity fields — which they do here (`a.clientUuid` is `@Column` and `c.uuid` is `@Id`). So this particular join **will work** in Hibernate 6 / JPA 2.1+.

**Revised severity: MEDIUM (not critical).** The query will execute at runtime in Hibernate 6. However:

- This is a non-standard use that bypasses the ORM association model. It is fragile: if `clientUuid` in `ApiClientAuditLog` is never declared as a proper FK (just a String column), Hibernate has no FK constraint awareness here, but the query itself is valid JPQL.
- The compile check passes (JPQL is not validated at compile time).
- Risk: This must be integration-tested at runtime. If the Hibernate dialect version bundled with Quarkus 3.30 has regressions or differences from the standard, the query may fail at first invocation rather than startup.

**Recommendation:** Add an integration test or at minimum a startup `@Startup` validation, or rewrite using a native query with explicit SQL (which is guaranteed to work in MariaDB with column-level joins). Flag for the implementation team to verify at runtime.

**This is now classified as MEDIUM risk, not a blocker for compilation, but must be runtime-verified.**

---

### 2.2 EndpointRegistryResource

| Check | Result |
|---|---|
| `@Path("/auth/endpoint-registry")` | PASS — line 20 |
| `@RolesAllowed({"admin:*", "ADMIN", "SYSTEM"})` | PASS — line 22 |
| GET returns full list via service | PASS |
| No logic — pure delegation | PASS |

---

### 2.3 EndpointRegistryService

| Check | Result |
|---|---|
| Scans at startup (`@Observes StartupEvent`) | PASS — line 59 |
| Caches result in `cachedEntries` (immutable list) | PASS — `Collections.unmodifiableList` line 128 |
| Combines class + method `@Path` | PASS — `basePath + normalizePath(methodPath.value())` lines 94–96 |
| Inherits class-level `@RolesAllowed` when method has none | PASS — fallback logic lines 114–116 |
| `@PermitAll` endpoints have `permitAll: true` and empty `rolesAllowed` | PASS — method-level (lines 105–107) and class-level (lines 111–113) |
| Domain derivation from package segments (reverse order) | PASS — `deriveDomain` checks segments in reverse order (most specific first), line 159 |

#### FINDING 2 — CDI proxy class scanning (MEDIUM)

`beanManager.getBeans(Object.class)` returns **all CDI beans**, not just JAX-RS resources. The filter `classPath = beanClass.getAnnotation(Path.class)` skips non-JAX-RS beans — but **CDI client proxies** created by Quarkus for normal-scoped beans (e.g., `@ApplicationScoped`, `@RequestScoped`) may be returned as separate `Bean<?>` entries, and their `getBeanClass()` returns the **real class** (not the proxy), so annotations are readable. This is by design in CDI 2.0+.

However, if any bean has a non-public `@GET`/`@POST` method, `getDeclaredMethods()` (line 87) would include them — but JAX-RS requires public methods, so this is unlikely to cause false positives.

**Risk:** Low in practice. The filter-by-`@Path` is the right guard. However, if two CDI bean types wrap the same resource (e.g., via inheritance), the same endpoint could appear twice. Recommend testing with the full running app.

#### FINDING 3 — Mixed @PermitAll/@RolesAllowed at method level (MINOR)

`LoginResource` has:
- Class level: no security annotation
- Methods: `@PermitAll` on `/login` and `/validate`, `@RolesAllowed({"ADMIN"})` on `/createsystemtoken`

The scanner correctly handles this case: method-level `@PermitAll` sets `effectivePermitAll = true`, method-level `@RolesAllowed` sets the roles. But the method `/createsystemtoken` has no class-level `@Path` skip guard. When `classPath == null` (no `@Path` on class), the bean is skipped entirely — so `LoginResource` (which has `@Path("/login")` on the class) will be scanned. Verified: `@Path("/login")` at class level. All three methods will be scanned correctly.

**Result: No bug.** Confirmed by reading `LoginResource` — class has `@Path("/login")`, method-level annotations override as expected.

#### FINDING 4 — `TokenResource` class-level `@PermitAll` correct propagation

`TokenResource` has `@PermitAll` at class level (line 36) and no `@RolesAllowed` on individual methods. The scanner correctly uses `classPermitAll = true` path → `effectivePermitAll = true`, `effectiveRoles = List.of()`. PASS.

The `POST /auth/token` endpoint will appear with `permitAll: true` and empty `rolesAllowed`. This is correct per REQ-RC-04.

---

## 3. Spec Coverage (REQ-BE-01 through REQ-BE-08)

| Req | Requirement | Status |
|---|---|---|
| REQ-BE-01 | `GET /auth/audit-log` with server-side pagination | PASS |
| REQ-BE-02 | Query params: `client_uuid`, `event_type` (comma-sep), `from`, `to` (ISO), `page` (0), `size` (50, max 200) | PASS |
| REQ-BE-03 | Response: `items` (with resolved `client_id`), `totalCount`, `page`, `size` | PASS |
| REQ-BE-04 | `@RolesAllowed({"admin:*", "ADMIN"})` | PASS (also includes SYSTEM, which is more permissive — acceptable) |
| REQ-BE-05 | `GET /auth/endpoint-registry` returning endpoints with method, path, rolesAllowed, permitAll, domain | PASS |
| REQ-BE-06 | Each entry: method, path, rolesAllowed (empty for @PermitAll), permitAll, domain | PASS — all five fields in `EndpointRegistryEntry` record |
| REQ-BE-07 | `@RolesAllowed({"admin:*", "ADMIN"})` | PASS (also includes SYSTEM) |
| REQ-BE-08 | Scans at startup and caches result | PASS |

**All 8 requirements covered.**

---

## 4. Endpoint Registry Spot-Check

### TokenResource (`@Path("/auth")`, class-level `@PermitAll`)
- `POST /auth/token` — expected: `permitAll: true`, `rolesAllowed: []`, domain: `Admin/Auth` (package `...security.apiclient` → segment `apiclient` → "Admin/Auth")
- Scanner: `classPermitAll = true`, no method-level override → `effectivePermitAll = true`, `effectiveRoles = []`. Domain: package `dk.trustworks.intranet.security.apiclient` → reverse scan: `apiclient` → "Admin/Auth". **CORRECT.**

### ClientManagementResource (`@Path("/auth/clients")`, `@RolesAllowed({"admin:*","ADMIN","SYSTEM"})`)
- `GET /auth/clients` — expected: `rolesAllowed: ["admin:*","ADMIN","SYSTEM"]`, `permitAll: false`, domain: `Admin/Auth`
- Scanner: classRoles = `["admin:*","ADMIN","SYSTEM"]`, no method-level override → inherits class roles. Domain: `apiclient` → "Admin/Auth". **CORRECT.**

### LoginResource (`@Path("/login")`, `@RequestScoped`, mixed method-level annotations)
- `GET /login` → method has `@PermitAll` → `permitAll: true`, `rolesAllowed: []`. Domain: package `dk.trustworks.intranet.apigateway.resources` → no segment matches any key in DOMAIN_MAP (`apigateway` and `resources` are not in the map) → domain: **"Other"**.
- This is a domain derivation gap. The `apigateway` package segment is not in the DOMAIN_MAP. The `/login` endpoint will show domain "Other" rather than something more descriptive like "Auth" or "Public".
- `GET /login/createsystemtoken` → method has `@RolesAllowed({"ADMIN"})` → `rolesAllowed: ["ADMIN"]`, `permitAll: false`. Domain: "Other" (same issue).
- `GET /login/validate` → method has `@PermitAll` → `permitAll: true`, `rolesAllowed: []`. **CORRECT behavior**, domain "Other" (minor label issue).

**Domain gap for `apigateway` package:** The DOMAIN_MAP does not include `apigateway` as a key. Resources in that package (LoginResource, VacationResource) will be classified as "Other". This is a cosmetic gap — the endpoint data is still accurate, just the domain label is less informative.

---

## 5. Issues Summary

| # | Severity | Location | Description |
|---|---|---|---|
| 1 | MEDIUM | `ApiClientRepository` lines 157–160 | JPQL `LEFT JOIN <Entity> ON` ad-hoc join. Valid in Hibernate 6/JPA 2.1+ but must be **runtime-verified** — compile does not validate JPQL. Not integration-tested in scope. |
| 2 | MEDIUM | `EndpointRegistryService` | CDI proxy/bean duplication risk. `beanManager.getBeans(Object.class)` scans all CDI beans; if any proxied resource class appears twice, endpoints may be duplicated. Must be verified at runtime. |
| 3 | LOW | `EndpointRegistryService.DOMAIN_MAP` | `apigateway` package not in the domain map. Resources under `dk.trustworks.intranet.apigateway.resources` (LoginResource, VacationResource) will show domain "Other" instead of a meaningful label. |
| 4 | LOW | `AuditLogResource` `@RolesAllowed` | Spec REQ-BE-04 and REQ-BE-07 list `"ADMIN"` + `"admin:*"` only. Implementation adds `"SYSTEM"`. This is more permissive than specified. Not a defect per se, but deviates from spec and should be explicit (decision logged). |

---

## 6. Not Verified

| Check | Why Not Verified | How to Verify |
|---|---|---|
| JPQL runtime execution | JPQL is not validated at compile time | Run `./mvnw test` with an integration test that calls `GET /auth/audit-log`; or start in dev mode and hit the endpoint with Postman/curl |
| Endpoint registry deduplication | Would require running the Quarkus app | Start with `./mvnw quarkus:dev`, call `GET /auth/endpoint-registry`, check for duplicates |
| Domain label completeness for all packages | Only three resources spot-checked | Review all resource classes under `apigateway`, `aggregates`, and `dao` packages and cross-reference DOMAIN_MAP |
| Flyway migration for `api_client_audit_log` table | Not in scope for this task (pre-existing table per SPEC-SEC-001) | Verify `src/main/resources/db/migration` has the migration that creates `api_client_audit_log` |

