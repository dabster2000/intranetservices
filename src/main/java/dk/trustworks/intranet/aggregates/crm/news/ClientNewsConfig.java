package dk.trustworks.intranet.aggregates.crm.news;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.Optional;

@ApplicationScoped
public class ClientNewsConfig {
    @ConfigProperty(name = "client-news.enabled", defaultValue = "false")
    boolean enabled;
    @ConfigProperty(name = "client-news.api-key")
    Optional<String> apiKey;
    @ConfigProperty(name = "client-news.daily-request-budget", defaultValue = "200")
    int dailyRequestBudget;

    // Call methods on this normal-scoped bean so Arc delegates to the contextual instance.
    boolean enabled() { return enabled; }
    Optional<String> apiKey() { return apiKey; }
    boolean configured() { return apiKey != null && apiKey.filter(k -> !k.isBlank()).isPresent(); }
    int budget() { return Math.max(1, Math.min(dailyRequestBudget, 1000)); }
}
