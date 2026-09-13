package dk.trustworks.intranet.aggregates.crm.news;

import dk.trustworks.intranet.aggregates.finance.health.IntercompanyClassificationCheck;
import dk.trustworks.intranet.aggregates.finance.jobs.EconomicRevenueImportBatchlet;
import dk.trustworks.intranet.aggregates.finance.jobs.OpexDistributionRefreshBatchlet;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.expenseservice.services.PromptBootstrapper;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real MariaDB/HTTP checks. Opt in with -Dclient-news.integration=true and a disposable crm_news_verify_* schema.
 * Both datasource URLs/credentials must be overridden; no client data or live provider is used. */
@QuarkusTest
@TestProfile(ClientNewsRepositoryIntegrationTest.IsolatedProfile.class)
@EnabledIfSystemProperty(named = "client-news.integration", matches = "true")
class ClientNewsRepositoryIntegrationTest {
    public static class IsolatedProfile implements QuarkusTestProfile {
        static final List<Class<?>> EXCLUDED_STARTUP_JOBS = List.of(
                OpexDistributionRefreshBatchlet.class, EconomicRevenueImportBatchlet.class,
                IntercompanyClassificationCheck.class, PromptBootstrapper.class);

        @Override public boolean disableApplicationLifecycleObservers() { return true; }

        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.flyway.migrate-at-start", "false",
                    "quarkus.flyway.repair-at-start", "false", "quarkus.scheduler.enabled", "false",
                    // Explicitly exclude cold-start observers; disabling the scheduler does not stop them.
                    "quarkus.arc.exclude-types", String.join(",", EXCLUDED_STARTUP_JOBS.stream().map(Class::getName).toList()),
                    "quarkus.mailer.mock", "true",
                    "quarkus.otel.sdk.disabled", "true",
                    "client-news.enabled", "true", "client-news.api-key", "fictional-test-key",
                    "cvtool.username", "test", "cvtool.password", "test");
        }
    }

    @Inject ClientNewsRepository repository;
    @Inject EntityManager em;
    @Inject ClientNewsConfig config;
    @InjectMock SlackService slackService;
    String id;
    ClientNewsRepository.Identity identity;
    final LocalDate day = LocalDate.of(2042, 1, 14);
    final Instant due = Instant.now().minusSeconds(60);

    @BeforeEach void seed() {
        for (Class<?> type : IsolatedProfile.EXCLUDED_STARTUP_JOBS) {
            assertFalse(Arc.container().instance(type).isAvailable(),
                    () -> "Unrelated startup job must be excluded: " + type.getSimpleName());
        }
        String database = QuarkusTransaction.requiringNew().call(() ->
                (String) em.createNativeQuery("SELECT DATABASE()").getSingleResult());
        assertTrue(database.startsWith("crm_news_verify_"), "Use an isolated, empty news test schema");
        assertTrue(config.enabled(), "The injected configuration must resolve through its CDI proxy");
        assertEquals("fictional-test-key", config.apiKey().orElseThrow());
        id = UUID.randomUUID().toString();
        identity = new ClientNewsRepository.Identity(id, "Fictional News Test A/S", "00000000");
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO client(uuid,name,cvr,type) VALUES (:id,:name,'00000000','CLIENT')")
                    .setParameter("id", id).setParameter("name", identity.name()).executeUpdate();
            em.createNativeQuery("INSERT INTO client_plan(client_uuid,status,created_at,created_by) "
                            + "VALUES (:id,'ACTIVE',UTC_TIMESTAMP(),:id)")
                    .setParameter("id", id).executeUpdate();
        });
    }

    @AfterEach void clean() {
        if (id == null) return;
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM client_news_state WHERE client_uuid=:id").setParameter("id", id).executeUpdate();
            em.createNativeQuery("DELETE FROM client_plan WHERE client_uuid=:id").setParameter("id", id).executeUpdate();
            em.createNativeQuery("DELETE FROM client WHERE uuid=:id").setParameter("id", id).executeUpdate();
            em.createNativeQuery("DELETE FROM client_news_request_budget WHERE budget_day=:day")
                    .setParameter("day", day).executeUpdate();
        });
        verifyNoInteractions(slackService);
    }

    @Test void concurrentWorkersHaveOneWinnerAndExpiredWriterCannotPublish() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier start = new CyclicBarrier(2);
            Callable<Optional<ClientNewsRepository.Lease>> claim = () -> {
                start.await(5, TimeUnit.SECONDS);
                return repository.claim(identity, due, day);
            };
            var first = pool.submit(claim);
            var second = pool.submit(claim);
            var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(Optional::isPresent).count());
            var old = results.stream().flatMap(Optional::stream).findFirst().orElseThrow();
            sql("UPDATE client_news_state SET lease_until=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE) WHERE client_uuid=:id");
            var replacement = repository.claim(identity, due, day).orElseThrow();
            assertNotEquals(old.token(), replacement.token());
            assertFalse(repository.complete(old, Instant.now(), List.of(item("stale")), false));
            repository.fail(old, "HTTP_ERROR", List.of());
            assertTrue(repository.complete(replacement, Instant.now(), List.of(item("current")), false));
            assertEquals("current", repository.read(id).items().getFirst().item().id());
        }
    }

    @Test void requestBudgetIsAtomicAndIncludesRepeatedAttempts() throws Exception {
        var lease = repository.claim(identity, due, day).orElseThrow();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 12; i++) attempts.add(() -> repository.reserveRequest(lease, day, 3));
            int allowed = 0;
            for (var result : pool.invokeAll(attempts)) if (result.get()) allowed++;
            assertEquals(3, allowed);
            assertFalse(repository.reserveRequest(lease, day, 3));
        }
    }

    @Test void failureKeepsSuccessfulCacheAndNextAttemptIsDeferred() {
        var first = repository.claim(identity, due, day).orElseThrow();
        assertTrue(repository.complete(first, Instant.now(), List.of(item("saved")), true));
        var saved = repository.read(id);
        assertEquals(ClientNewsDTO.Status.READY, saved.status());
        assertTrue(saved.coverageLimited());
        assertTrue(repository.claim(identity, due, day).isEmpty(), "Successful company must not run twice that morning");
        var retry = repository.claim(identity, Instant.now().plusSeconds(60), day).orElseThrow();
        repository.fail(retry, "HTTP_ERROR", List.of());
        var failed = repository.read(id);
        assertEquals(ClientNewsDTO.Status.FAILED, failed.status());
        assertEquals(saved.succeeded(), failed.succeeded());
        assertEquals(saved.items(), failed.items());
        assertTrue(repository.claim(identity, Instant.now().plusSeconds(60), day).isEmpty());
    }

    @Test void removingPlanEligibilityFencesInFlightCompletion() {
        var lease = repository.claim(identity, due, day).orElseThrow();
        sql("UPDATE client_plan SET status='NONE' WHERE client_uuid=:id");
        assertFalse(repository.reserveRequest(lease, day, 10));
        assertFalse(repository.complete(lease, Instant.now(), List.of(item("obsolete")), false));
        assertFalse(repository.read(id).eligible());
    }

    @Test @TestSecurity(user = "reader", roles = "accounts:read")
    void endpointReturnsStoredProjectionAnd404ForUnknownClient() {
        var lease = repository.claim(identity, due, day).orElseThrow();
        repository.complete(lease, Instant.now(), List.of(item("stored")), true);
        given().get("/clients/" + id + "/news").then().statusCode(200)
                .body("eligible", is(true)).body("status", is("READY"))
                .body("coverageLimited", is(true)).body("items[0].id", is("stored"))
                .body("lastSuccessfulCheckAt", notNullValue())
                .body("items[0].storyKey", nullValue());
        given().get("/clients/" + UUID.randomUUID() + "/news").then().statusCode(404);
        given().get("/clients/not-a-uuid/news").then().statusCode(400);
    }

    @Test @TestSecurity(user = "unprivileged", roles = "users:read")
    void endpointDeniesUnrelatedScope() {
        given().get("/clients/" + id + "/news").then().statusCode(403);
    }

    @Test void endpointRequiresAuthentication() {
        given().get("/clients/" + id + "/news").then().statusCode(401);
    }

    void sql(String statement) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(statement)
                .setParameter("id", id).executeUpdate());
    }
    ClientNewsDTO.StoredItem item(String newsId) {
        return new ClientNewsDTO.StoredItem(new ClientNewsDTO.Item(newsId, "Fictional project announcement",
                "https://example.com/news/" + newsId, "Example", Instant.now().minus(Duration.ofDays(1)),
                "A fictional source excerpt.", ClientNewsDTO.Category.INVESTMENT, ClientNewsDTO.Scope.COMPANY), newsId);
    }
}
