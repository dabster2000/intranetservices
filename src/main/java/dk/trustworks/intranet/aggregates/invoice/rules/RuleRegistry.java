package dk.trustworks.intranet.aggregates.invoice.rules;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;

@ApplicationScoped
public class RuleRegistry {
    @Inject Instance<ContractRule> rules;
    public Optional<ContractRule> find(String code) {
        for (ContractRule r : rules) if (r.code().equals(code)) return Optional.of(r);
        return Optional.empty();
    }
}
