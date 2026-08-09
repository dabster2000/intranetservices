# Quarkus Backend — Quick Reference

Java 21, Quarkus 3.36.x, Hibernate ORM with Panache, MariaDB 10.x, Flyway migrations.

## REST Resource Routing

Match the API path from the bug report to the resource class:

```
/bug-reports             → aggregates/bugreport/resources/BugReportResource.java
/users                   → apigateway/resources/UserResource.java (+ RoleResource, StatusResource, SalaryResource, etc.)
/clients                 → apigateway/resources/ClientResource.java
/clients/cxo             → aggregates/finance/resources/CxoClientResource.java
/contracts               → apigateway/resources/ContractResource.java
/invoices                → aggregates/invoice/resources/InvoiceResource.java
/invoices/bonuses        → aggregates/invoice/bonus/resources/InvoiceBonusResource.java
/invoices/eligibility    → aggregates/invoice/bonus/resources/BonusEligibilityResource.java
/sales/leads             → apigateway/resources/SalesResource.java
/projects                → apigateway/resources/ProjectResource.java
/tasks                   → apigateway/resources/TaskResource.java
/finance/cxo             → aggregates/finance/resources/CxoFinanceResource.java
/delivery/cxo            → aggregates/delivery/resources/CxoDeliveryResource.java
/company/{uuid}/crm      → aggregates/crm/resources/CrmResource.java
/company/{uuid}/utilization → aggregates/utilization/resources/UtilizationResource.java
/company/{uuid}/revenue  → aggregates/revenue/resources/RevenueResource.java
/bonus/tw                → apigateway/resources/TwBonusResource.java
/bonus/yourpartoftrustworks → apigateway/resources/YourPartOfTrustworksResource.java
/teams                   → apigateway/resources/TeamResource.java
/expenses                → expenseservice/resources/ExpenseResource.java
/templates               → documentservice/resources/TemplateResource.java
/knowledge/conferences   → aggregates/conference/resources/ConferenceResource.java
/practices               → resources/PracticeResource.java
/accounting              → aggregates/accounting/resources/AccountingResource.java
/auth                    → security/apiclient/TokenResource.java
/auth/clients            → security/apiclient/ClientManagementResource.java
```

All paths are relative to `src/main/java/dk/trustworks/intranet/`.

## Architecture Pattern

```
Resource (@Path, @RolesAllowed) → Service (@ApplicationScoped) → Entity (Panache)
Request DTO → validation (@Valid) → service method → Response DTO
```

- User identity: `X-Requested-By` header → `RequestHeaderHolder` (request-scoped CDI bean)
- Auth: `@RolesAllowed({"domain:read"})` on every endpoint
- Optimistic locking: `If-Match` header with ISO datetime

## Common Bug Patterns

**404 NOT FOUND**: Check `@Path` annotations — nested paths combine class + method `@Path`.
→ Verify the full path: class `@Path("/users")` + method `@Path("/{uuid}/salaries")` = `/users/{uuid}/salaries`.

**403 FORBIDDEN**: Check `@RolesAllowed` — scope mismatch between BFF client and endpoint.
→ Fix: verify the BFF API client has the required scope.

**500 / NPE**: Check null-safety on Panache queries — `find("uuid", uuid).firstResult()` returns null if not found.
→ Fix: add null check or use `findByIdOptional`.

**409 CONFLICT**: Optimistic locking failure or invalid state transition.
→ Check `If-Match` header handling and state machine logic.

**WRONG DATA / MISSING FIELDS**: Check DTO mapping — field name mismatch between entity and DTO.
→ Check `@JsonProperty` annotations and constructor/record field names.

## Key Conventions

- Flyway migrations: `src/main/resources/db/migration/V{number}__Description.sql`
- DB is **READ-ONLY by default** — write operations need explicit transaction
- Entities use Panache: `PanacheEntityBase` with `@Entity`, `@Table`
- Native queries: `em.createNativeQuery(sql)` for complex DB operations
- DateUtils / NumberUtils for formatting

## Build Commands

```
./mvnw compile                                                  # Compile
./mvnw test -DexcludedGroups=io.quarkus.test.junit.QuarkusTest  # DB-free tier (~45s) — default
./mvnw test -Dsurefire.forkCount=4 -Dsurefire.maxHeap=2g        # full tier, incl. @QuarkusTest
```

**Test memory:** peak RAM ≈ `surefire.forkCount` × `surefire.maxHeap` (defaults
`1C` × `1g` in `pom.xml`). `forkCount=1C` is one JVM per core — 14 on a 14-core
Mac. Never drop the `-Xmx` from the surefire `argLine`: without it each fork takes
the JVM default ceiling of 25% of physical RAM and a plain `./mvnw test` reaches
65 GB, which is enough to have the macOS OOM killer take the machine down.
