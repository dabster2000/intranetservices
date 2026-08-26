package dk.trustworks.intranet.scheduling;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.ScheduledExecution;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.scheduler.SkippedExecution;
import io.quarkus.scheduler.Trigger;
import io.quarkus.scheduler.common.runtime.ScheduledInvoker;
import io.quarkus.scheduler.common.runtime.SkipPredicateInvoker;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behaviour of {@link SchedulerShutdownGuard}: a tick that fires during the
 * shutdown drain must be skipped cleanly rather than exploding on a
 * torn-down persistence unit.
 * <p>
 * The closing test drives the REAL
 * {@link SkipPredicateInvoker} — the same wrapper Quarkus builds around every
 * {@code skipExecutionIf} job — so it exercises the actual framework contract
 * rather than a hand-rolled stand-in.
 * <p>
 * DB-free and Quarkus-free by design: the production defect only reproduces
 * when the {@code EntityManagerFactory} is <em>gone</em>, so booting a real
 * persistence unit would not help even if a local database existed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerShutdownGuardTest {

    /**
     * The exact production failure: {@code JPAConfig.getEntityManagerFactory}
     * throws this once {@code JPAConfig.shutdown()} has cleared its
     * persistence-unit map.
     */
    private static final String EMF_GONE =
            "Unable to find an EntityManagerFactory for persistence unit '<default>'.";

    @Mock
    Scheduler scheduler;

    @Mock
    ScheduledExecution execution;

    @Mock
    Trigger trigger;

    @Mock
    Event<SkippedExecution> skippedEvent;

    SchedulerShutdownGuard guard;

    @BeforeEach
    void setUp() {
        guard = new SchedulerShutdownGuard();
        guard.scheduler = scheduler;
        when(execution.getTrigger()).thenReturn(trigger);
        when(execution.getFireTime()).thenReturn(Instant.now());
        when(trigger.getId()).thenReturn("recruitment-scheduling-outbox");
    }

    @Test
    @DisplayName("before shutdown the predicate skips nothing and jobs run normally")
    void predicate_is_inert_before_shutdown() {
        assertFalse(guard.isShuttingDown(), "guard must start un-tripped");
        assertFalse(guard.test(execution),
                "a normal tick must never be skipped — a guard that skips during normal "
                        + "operation would silently stop every scheduled job");
    }

    @Test
    @DisplayName("ShutdownEvent trips the flag and pauses the scheduler fleet-wide")
    void shutdown_sets_flag_and_pauses_scheduler() {
        guard.onShutdown(new ShutdownEvent());

        assertTrue(guard.isShuttingDown(), "the shutdown flag must be set");
        // This is layer 1: pausing makes SimpleScheduler.checkTriggers()
        // early-return, which stops dispatch for EVERY scheduled job at once.
        verify(scheduler).pause();
    }

    @Test
    @DisplayName("after shutdown the predicate reports every tick as skippable")
    void predicate_skips_after_shutdown() {
        guard.onShutdown(new ShutdownEvent());

        assertTrue(guard.test(execution),
                "once shutting down, the scheduler's invoker must skip the execution "
                        + "instead of invoking a method that would touch a closed EMF");
    }

    @Test
    @DisplayName("a pause() failure still trips the flag and never breaks shutdown")
    void pause_failure_is_survivable() {
        // SimpleScheduler.pause() throws when the scheduler was never started
        // (scheduler disabled, HALTED start mode, some test contexts).
        doThrow(new IllegalStateException("Scheduler was not started"))
                .when(scheduler).pause();

        assertDoesNotThrow(() -> guard.onShutdown(new ShutdownEvent()),
                "an exception escaping the observer would abort the rest of the "
                        + "shutdown sequence");
        assertTrue(guard.isShuttingDown(),
                "the flag must be set even when pause() fails — layer 2 has to keep "
                        + "working on its own");
        assertTrue(guard.test(execution), "and the predicate must still skip");
    }

    @Test
    @DisplayName("ShutdownEvent is idempotent — a second one is harmless")
    void shutdown_is_idempotent() {
        guard.onShutdown(new ShutdownEvent());
        assertDoesNotThrow(() -> guard.onShutdown(new ShutdownEvent()));
        assertTrue(guard.isShuttingDown());
    }

    /**
     * The closing test, and the one that ties directly to the production
     * incident of 2026-08-24 21:58:39Z.
     * <p>
     * The delegate stands in for a scheduled method whose first act is to
     * touch the persistence unit. Before the guard trips it blows up exactly
     * as production did — that half REPRODUCES the bug, and would keep passing
     * if the guard were deleted. After the guard trips, the real
     * {@link SkipPredicateInvoker} must never reach the delegate at all.
     */
    @Test
    @DisplayName("a tick during shutdown never reaches a method that would touch the dead EMF")
    void tick_during_shutdown_is_skipped_instead_of_throwing() throws Exception {
        AtomicBoolean delegateWasInvoked = new AtomicBoolean(false);
        ScheduledInvoker doomedJob = exec -> {
            delegateWasInvoked.set(true);
            // Precisely what JPAConfig throws once the PU map is cleared.
            throw new IllegalArgumentException(EMF_GONE);
        };
        SkipPredicateInvoker invoker =
                new SkipPredicateInvoker(doomedJob, guard, skippedEvent);

        // --- 1. Reproduce the defect: without the guard tripped, the tick runs
        //        and dies on the missing EntityManagerFactory.
        //        DelegateInvoker.invokeDelegate catches Throwable and returns a
        //        FAILED CompletionStage rather than throwing — which is exactly
        //        why the production line reads
        //        "CompletionException: IllegalArgumentException: Unable to find
        //        an EntityManagerFactory ...". So assert on the stage, not on a
        //        thrown exception.
        CompletionStage<Void> beforeGuard = invoker.invoke(execution);
        assertTrue(delegateWasInvoked.get(),
                "sanity check: the doomed job must genuinely run, otherwise the "
                        + "second half of this test proves nothing");
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> beforeGuard.toCompletableFuture().get(),
                "the tick must fail, reproducing the production symptom");
        assertInstanceOf(IllegalArgumentException.class, failure.getCause(),
                "must be the production failure, not some other error");
        assertTrue(failure.getCause().getMessage().contains("Unable to find an EntityManagerFactory"),
                "must be the production failure, not some other error");

        // --- 2. Now the drain begins.
        delegateWasInvoked.set(false);
        guard.onShutdown(new ShutdownEvent());

        // --- 3. The same tick, through the same real invoker, is now skipped
        //        cleanly: no exception, and the method is never entered.
        CompletionStage<Void> result = assertDoesNotThrow(
                () -> invoker.invoke(execution),
                "a tick during shutdown must be skipped, not thrown — the production "
                        + "symptom was an ERROR from StatusEmitterInvoker");
        assertNotNull(result, "the invoker contract says the result is never null");
        assertFalse(delegateWasInvoked.get(),
                "THE POINT: the scheduled method must never be entered during shutdown, "
                        + "so it can never ask for the closed EntityManagerFactory");
        assertDoesNotThrow(() -> result.toCompletableFuture().get(),
                "the skipped execution completes normally rather than exceptionally");
    }

    @Test
    @DisplayName("resetForTest is the only way back — production has no un-shutdown path")
    void reset_is_test_only_seam() {
        guard.onShutdown(new ShutdownEvent());
        assertTrue(guard.isShuttingDown());

        guard.resetForTest();

        assertFalse(guard.isShuttingDown());
        assertFalse(guard.test(execution));
        verify(scheduler, never()).resume();
    }
}
