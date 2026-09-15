package dk.trustworks.intranet.aggregates.crm.calendar.services;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/** Post-commit rule changes request a complete replay; old evidence is reconciled on that replay. */
@ApplicationScoped
@JBossLog
public class CalendarRecoveryService {
    @Inject CalendarConsentService consent;
    @Inject CalendarSyncStateService state;

    public void requestAfterRulesChanged() {
        try {
            state.requestFullRead(QuarkusTransaction.requiringNew().call(consent::consentedUserUuids));
        } catch (RuntimeException failure) {
            // The edit already committed. A failed derived refresh must not make the UI retry it.
            log.warn("Calendar full replay request failed: RULE_CHANGE_REFRESH_FAILED; weekly full read will retry");
        }
    }
}
