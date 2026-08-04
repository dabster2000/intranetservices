package dk.trustworks.intranet.aggregates.consultant.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.consultant.model.ConsultantProfile;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.cvtool.entity.CvToolEmployeeCv;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Generates the AI half of a consultant sales profile, always <b>off</b> the request thread.
 *
 * <p>The defect this class exists to end: the previous implementation called OpenAI in-line,
 * sequentially, inside one {@code @Transactional} read. A dashboard batch of three uncached
 * consultants could block for 3&nbsp;×&nbsp;110&nbsp;s while holding a pooled Agroal connection,
 * so both client hops timed out at 30&nbsp;s and every slide lost its data — while the
 * abandoned request happily finished and committed.
 *
 * <p>Three guards prevent a generation storm:
 * <ol>
 *   <li><b>In-JVM single flight</b> — {@link #inFlight}: at most one generation per consultant
 *       per JVM.</li>
 *   <li><b>Time backoff</b> — the read path only enqueues when
 *       {@code ConsultantProfile.shouldAttempt(...)} is true. This matters because a failure
 *       deliberately leaves {@code generated_at} NULL (so a broken profile is never served as
 *       fresh), which makes {@code isStale()} permanently true.</li>
 *   <li><b>Cross-instance upsert</b> — every write is a single
 *       {@code INSERT … ON DUPLICATE KEY UPDATE}, so two ECS tasks racing on the same consultant
 *       cannot produce a primary-key violation.</li>
 * </ol>
 *
 * <p><b>Threading note.</b> {@link ManagedExecutor} propagates the submitting HTTP request's CDI
 * context, which is terminated by the time the worker runs — a bare Panache read on the worker
 * would then die with "neither a transaction nor a CDI request context is active". That is why
 * <em>every</em> database touch in {@link #generateOne(String)} sits inside its own
 * {@code QuarkusTransaction.requiringNew()} block. Do not add an unwrapped repository call here.
 * {@code getProfiles} also refuses to run inside a transaction, so no JTA context can ride along
 * onto the worker either.
 */
@JBossLog
@ApplicationScoped
public class ConsultantProfileGenerationService {

    /**
     * On a reasoning-class model this budget covers hidden reasoning FIRST and the visible answer
     * second. 4096 with no effort pin is the exact configuration that returned no output text on
     * 2026-08-01; 8192 matches the {@code AiIntakeGenerationService} precedent, and the input here
     * (~5k tokens of Danish CV JSON) is larger than that one. The budget is a spend ceiling, not a
     * target: a successful call still bills only the tokens it uses.
     */
    static final int MAX_OUTPUT_TOKENS = 8192;

    private static final String SCHEMA_NAME = "consultant_profile";
    /** Failure code for "this consultant has no CV row at all" — not a model failure. */
    static final String CODE_NO_CV = "no-cv";

    /** Single-flight per consultant: at most one in-flight generation per uuid per JVM. */
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

    /**
     * Bounds OpenAI fan-out from the request path: a dashboard batch of 3 never saturates the
     * ManagedExecutor, and 100 concurrent users cannot fan out to 300 concurrent model calls.
     */
    private final Semaphore permits = new Semaphore(2);

    @Inject
    ManagedExecutor managedExecutor;

    @Inject
    OpenAIService openAIService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EntityManager em;

    /**
     * Deliberately NOT the global {@code openai.model}: that default is {@code gpt-5-nano}, which
     * spent its whole output budget on hidden reasoning and answered 2xx with no output text
     * (staging 2026-08-01), and it retires 2026-12-11. This call composes customer-facing ENGLISH
     * sales copy from ~13 KB of Danish CV JSON — synthesis, ranking and translation in one.
     */
    @ConfigProperty(name = "openai.consultant-profile-model", defaultValue = "gpt-5.6-terra")
    String profileModel;

    /**
     * {@code reasoning.effort} for the profile call. Declared {@code Optional<String>}
     * deliberately: an EMPTY value must mean "omit the reasoning node" (required if
     * {@link #profileModel} is ever pointed at a non-reasoning gpt-4o-family model, which rejects
     * the node). A plain {@code String} cannot express that — SmallRye converts an empty-but-present
     * value to null and, because the raw value is present, the {@code defaultValue} never rescues
     * it, so the application fails to boot with SRCFG00040.
     */
    @ConfigProperty(name = "openai.consultant-profile-reasoning-effort", defaultValue = "low")
    Optional<String> profileReasoningEffort;

    /** Master kill switch for every OpenAI call in this feature (on-demand and pre-warm). */
    @ConfigProperty(name = "consultant-profile.generation.enabled", defaultValue = "true")
    boolean generationEnabled;

    @ConfigProperty(name = "consultant-profile.generation.retry-backoff-minutes", defaultValue = "30")
    int retryBackoffMinutes;

    @ConfigProperty(name = "consultant-profile.generation.max-attempts", defaultValue = "3")
    int maxAttempts;

    /**
     * Everything the model round-trip needs, copied out of the entity so nothing detached is
     * dereferenced after its transaction closed.
     */
    public record GenerationInput(
            String useruuid,
            String employeeName,
            String employeeTitle,
            String employeeProfile,
            String cvDataJson,
            LocalDateTime cvLastUpdatedAt
    ) {
    }

    /** The backoff the read path must honour before it re-enqueues a failed consultant. */
    public int retryBackoffMinutes() {
        return retryBackoffMinutes;
    }

    public boolean isGenerationEnabled() {
        return generationEnabled;
    }

    /**
     * Schedules a background generation for one consultant. Never blocks, never throws — the read
     * path must not fail because generation could not be scheduled.
     *
     * @return true when a task was actually submitted
     */
    public boolean enqueue(String useruuid) {
        if (!generationEnabled) {
            return false;
        }
        if (useruuid == null || useruuid.isBlank()) {
            return false;
        }
        if (inFlight.putIfAbsent(useruuid, Boolean.TRUE) != null) {
            return false;
        }
        try {
            managedExecutor.submit(() -> {
                try {
                    generateOne(useruuid);
                } catch (Exception e) {
                    log.errorf(e, "Consultant profile generation failed for %s", useruuid);
                } finally {
                    inFlight.remove(useruuid);
                }
            });
            return true;
        } catch (RuntimeException e) {
            inFlight.remove(useruuid);
            log.errorf(e, "Could not schedule consultant profile generation for %s", useruuid);
            return false;
        }
    }

    /**
     * Runs one full generation synchronously on the calling thread, in three phases so that no
     * database connection is ever held across the model round-trip. Also used directly by the
     * nightly pre-warm job, so the empty-output guard, the permit semaphore and the upsert apply
     * identically on both paths.
     *
     * @throws IllegalStateException if a transaction is active — that would defeat the whole design
     */
    public void generateOne(String useruuid) {
        if (QuarkusTransaction.isActive()) {
            throw new IllegalStateException("generateOne must not be called inside a transaction");
        }

        // Phase 1 — inputs, in a transaction that COMPLETES before the model call.
        GenerationInput in = QuarkusTransaction.requiringNew().call(() -> loadInput(useruuid));
        if (in == null) {
            QuarkusTransaction.requiringNew().run(() -> markUnavailable(useruuid, CODE_NO_CV));
            return;
        }

        // Phase 2 — the OpenAI round-trip. No transaction, no pooled connection held.
        String json;
        try {
            if (!permits.tryAcquire(30, TimeUnit.SECONDS)) {
                log.warnf("Consultant profile generation skipped (no permit) for %s", useruuid);
                return; // no DB write: the next read re-enqueues once the backoff has elapsed
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warnf("Consultant profile generation interrupted while queueing for %s", useruuid);
            return;
        }
        try {
            json = callModel(in);
        } finally {
            permits.release();
        }

        // Phase 3 — validate (pure), then upsert in a fresh short transaction.
        ConsultantProfileResponseValidator.Result result =
                ConsultantProfileResponseValidator.validate(json, objectMapper);
        if (result instanceof ConsultantProfileResponseValidator.Rejected rejected) {
            // Never log the prompt or the output body — the uuid and the code are enough.
            log.warnf("Consultant profile generation rejected for %s: %s", useruuid, rejected.code());
            QuarkusTransaction.requiringNew().run(() -> recordFailure(useruuid, rejected.code()));
            return;
        }
        ConsultantProfileResponseValidator.Accepted accepted =
                (ConsultantProfileResponseValidator.Accepted) result;
        QuarkusTransaction.requiringNew().run(() -> persist(in, accepted));
        log.debugf("Consultant profile generated for %s", useruuid);
    }

    // ---- Phase 1 ---------------------------------------------------------------

    /** @return null when the consultant has no CV row at all */
    GenerationInput loadInput(String useruuid) {
        CvToolEmployeeCv cv = pickCv(CvToolEmployeeCv.list("useruuid", useruuid));
        if (cv == null) {
            return null;
        }
        return new GenerationInput(useruuid, cv.getEmployeeName(), cv.getEmployeeTitle(),
                cv.getEmployeeProfile(), cv.getCvDataJson(), cv.getCvLastUpdatedAt());
    }

    /**
     * A consultant may hold several CV rows (language variants). Pick deterministically —
     * most recently updated first — so the read path and the write path always agree on the
     * {@code cv_updated_at} that staleness is measured against.
     */
    public static CvToolEmployeeCv pickCv(List<CvToolEmployeeCv> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .max(Comparator
                        .comparing(CvToolEmployeeCv::getCvLastUpdatedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(CvToolEmployeeCv::getUuid,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    // ---- Phase 2 ---------------------------------------------------------------

    /**
     * The model round-trip. Package-private so a plain JUnit test can assert that the configured
     * model, the pinned reasoning effort and the raised output budget actually reach
     * {@code OpenAIService}.
     *
     * <p>{@code refusalFallbackJson} is deliberately {@code null}: the previous value
     * {@code {"pitch":"","industries":[],"topSkills":[]}} parsed cleanly and cached an empty
     * profile for seven days. With null, a refusal comes back as {@code "{}"} and is caught by
     * the validator. {@code store=false} because real employee names and full CVs are in the
     * prompt.
     */
    String callModel(GenerationInput in) {
        return openAIService.askQuestionWithSchema(
                ConsultantProfilePrompts.SYSTEM_PROMPT,
                ConsultantProfilePrompts.userMessage(in),
                ConsultantProfilePrompts.schema(),
                SCHEMA_NAME,
                null,
                profileModel,
                MAX_OUTPUT_TOKENS,
                false,
                reasoningEffortOrNull());
    }

    /** The effort to send, or null to omit the reasoning node entirely. */
    String reasoningEffortOrNull() {
        return profileReasoningEffort.filter(effort -> !effort.isBlank()).orElse(null);
    }

    // ---- Phase 3 ---------------------------------------------------------------

    /**
     * Single upsert: removes the assigned-{@code @Id} duplicate-key race entirely, so an
     * on-demand enqueue and the nightly pre-warm job can touch the same consultant concurrently
     * without either of them blowing up.
     */
    void persist(GenerationInput in, ConsultantProfileResponseValidator.Accepted accepted) {
        String industriesJson = writeArray(accepted.industries());
        String skillsJson = writeArray(accepted.topSkills());
        LocalDateTime now = LocalDateTime.now();
        em.createNativeQuery("""
                        INSERT INTO consultant_profiles
                            (useruuid, pitch_text, industries_json, top_skills_json,
                             generated_at, cv_updated_at, status, generation_attempts,
                             last_attempt_at, last_error)
                        VALUES (:uuid, :pitch, :industries, :skills, :now, :cvUpdatedAt, 'READY', 0, :now, NULL)
                        ON DUPLICATE KEY UPDATE
                            pitch_text = VALUES(pitch_text),
                            industries_json = VALUES(industries_json),
                            top_skills_json = VALUES(top_skills_json),
                            generated_at = VALUES(generated_at),
                            cv_updated_at = VALUES(cv_updated_at),
                            status = 'READY',
                            generation_attempts = 0,
                            last_attempt_at = VALUES(last_attempt_at),
                            last_error = NULL
                        """)
                .setParameter("uuid", in.useruuid())
                .setParameter("pitch", accepted.pitch())
                .setParameter("industries", industriesJson)
                .setParameter("skills", skillsJson)
                .setParameter("now", now)
                .setParameter("cvUpdatedAt", in.cvLastUpdatedAt())
                .executeUpdate();
    }

    /**
     * Bookkeeping-only failure write: {@code generated_at}, {@code pitch_text},
     * {@code industries_json} and {@code top_skills_json} are left untouched, so a previously
     * good profile survives a failed refresh and is still served while the retry backoff runs.
     * The {@code status} assignment is listed BEFORE the increment so it reads the old counter —
     * MariaDB evaluates ON DUPLICATE KEY assignments left to right.
     */
    void recordFailure(String useruuid, String code) {
        LocalDateTime now = LocalDateTime.now();
        String initialStatus = maxAttempts <= 1
                ? ConsultantProfile.STATUS_UNAVAILABLE
                : ConsultantProfile.STATUS_PENDING;
        em.createNativeQuery("""
                        INSERT INTO consultant_profiles
                            (useruuid, status, generation_attempts, last_attempt_at, last_error)
                        VALUES (:uuid, :initialStatus, 1, :now, :code)
                        ON DUPLICATE KEY UPDATE
                            status = CASE WHEN generation_attempts + 1 >= :maxAttempts
                                          THEN 'UNAVAILABLE' ELSE 'PENDING' END,
                            generation_attempts = generation_attempts + 1,
                            last_attempt_at = VALUES(last_attempt_at),
                            last_error = VALUES(last_error)
                        """)
                .setParameter("uuid", useruuid)
                .setParameter("initialStatus", initialStatus)
                .setParameter("now", now)
                .setParameter("code", code)
                .setParameter("maxAttempts", maxAttempts)
                .executeUpdate();
    }

    /**
     * Parks a consultant that can never produce a profile (no CV row). Attempts are set to the
     * cap so the row stays parked rather than flipping back to PENDING on the next failure.
     */
    void markUnavailable(String useruuid, String code) {
        LocalDateTime now = LocalDateTime.now();
        em.createNativeQuery("""
                        INSERT INTO consultant_profiles
                            (useruuid, status, generation_attempts, last_attempt_at, last_error)
                        VALUES (:uuid, 'UNAVAILABLE', :attempts, :now, :code)
                        ON DUPLICATE KEY UPDATE
                            status = 'UNAVAILABLE',
                            generation_attempts = VALUES(generation_attempts),
                            last_attempt_at = VALUES(last_attempt_at),
                            last_error = VALUES(last_error)
                        """)
                .setParameter("uuid", useruuid)
                .setParameter("attempts", Math.max(maxAttempts, 1))
                .setParameter("now", now)
                .setParameter("code", code)
                .executeUpdate();
    }

    private String writeArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            // Cannot happen for a List<String>; degrade to an empty array rather than poisoning
            // the JSON column with the literal "null" that caused the original defect.
            log.warn("Could not serialise consultant profile labels — writing an empty array");
            return "[]";
        }
    }
}
