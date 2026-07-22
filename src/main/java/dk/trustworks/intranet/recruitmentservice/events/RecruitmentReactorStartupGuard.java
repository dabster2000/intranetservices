package dk.trustworks.intranet.recruitmentservice.events;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * Seeds every registered reactor's watermark to the current stream head at
 * boot (no-op for reactors that already have an offset row). This is the
 * "offsets seeded to current head at deploy" guarantee (plan §2): a newly
 * deployed reactor starts from <em>now</em> — it never replays the history
 * that accumulated before it existed.
 * <p>
 * Runs at every startup so the seeding cannot be forgotten when a later
 * phase adds a reactor; existing reactors are untouched.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentReactorStartupGuard {

    @Inject
    Instance<RecruitmentReactor> reactors;

    void onStart(@Observes StartupEvent event) {
        for (RecruitmentReactor reactor : reactors) {
            try {
                reactor.ensureOffsetRowSeededToHead();
                log.debugf("Recruitment reactor %s offset row ensured", reactor.name());
            } catch (Exception e) {
                // Never block boot — the catch-up batchlet re-runs the same
                // seeding before every sweep.
                log.errorf(e, "Could not seed offset row for recruitment reactor %s", reactor.name());
            }
        }
    }
}
