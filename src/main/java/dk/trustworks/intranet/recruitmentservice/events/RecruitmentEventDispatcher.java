package dk.trustworks.intranet.recruitmentservice.events;

import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * Fans freshly committed recruitment events out to every registered
 * {@link RecruitmentReactor} (live path).
 * <p>
 * A single EventBus consumer exists on purpose: {@code @ConsumeEvent} is not
 * discovered on methods inherited from a non-bean abstract class, so putting
 * the annotation on the reactor base would silently register nothing. The
 * dispatcher owns the one consumer and delegates; reactors stay plain CDI
 * beans.
 * <p>
 * One reactor failing must never starve the others — each delivery is
 * isolated (own transaction inside {@code deliverLive}, own catch here);
 * failed deliveries are retried by the catch-up batchlet.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentEventDispatcher {

    @Inject
    Instance<RecruitmentReactor> reactors;

    @ConsumeEvent(value = RecruitmentEventRecorder.EVENT_BUS_ADDRESS, blocking = true, ordered = true)
    public void onRecruitmentEvent(Long seq) {
        for (RecruitmentReactor reactor : reactors) {
            try {
                reactor.deliverLive(seq);
            } catch (Exception e) {
                log.errorf(e, "Reactor %s failed live delivery of recruitment event seq %d — catch-up will retry",
                        reactor.name(), seq);
            }
        }
    }
}
