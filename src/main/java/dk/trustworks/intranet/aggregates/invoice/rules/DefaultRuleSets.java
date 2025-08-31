package dk.trustworks.intranet.aggregates.invoice.rules;

import dk.trustworks.intranet.contracts.model.enums.ContractType;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class DefaultRuleSets {

    public List<RuleBinding> forType(ContractType type) {
        return switch (type) {
            case PERIOD -> List.of();
            case SKI0217_2021 -> List.of(
                RuleBinding.of("STAIR_PERCENT", 10, Map.of(
                    "applyOn", "SUBTOTAL_BEFORE",
                    "label", "SKI trappe-rabat",
                    "tiers", List.of(
                        Map.of("threshold", 0,      "percent", 0.0),
                        Map.of("threshold", 100000, "percent", 1.0),
                        Map.of("threshold", 200000, "percent", 1.5),
                        Map.of("threshold", 300000, "percent", 2.0)
                    )
                )),
                RuleBinding.of("PERCENT_OF_NET", 20, Map.of(
                    "applyOn", "SUBTOTAL_AFTER",
                    "label", "SKI administrationsgebyr",
                    "percent", 2.0
                )),
                RuleBinding.of("FIXED_FEE", 30, Map.of(
                    "label", "Faktureringsgebyr",
                    "amount", 2000.0
                ))
            );
            case SKI0215_2025 -> List.of(
                    RuleBinding.of("PERCENT_OF_NET", 20, Map.of(
                            "applyOn", "SUBTOTAL_AFTER",
                            "label", "SKI administrationsgebyr",
                            "percent", 2.0
                    )),
                    RuleBinding.of("FIXED_FEE", 30, Map.of(
                            "label", "Faktureringsgebyr",
                            "amount", 2000.0
                    ))
            );
            case SKI0217_2025 -> List.of(
                    RuleBinding.of("STAIR_PERCENT", 10, Map.of(
                            "applyOn", "SUBTOTAL_BEFORE",
                            "label", "SKI trappe-rabat",
                            "tiers", List.of(
                                    Map.of("threshold", 0,      "percent", 0.0),
                                    Map.of("threshold", 100000, "percent", 1.0),
                                    Map.of("threshold", 200000, "percent", 1.5),
                                    Map.of("threshold", 300000, "percent", 2.0)
                            )
                    )),
                    RuleBinding.of("PERCENT_OF_NET", 20, Map.of(
                            "applyOn", "SUBTOTAL_AFTER",
                            "label", "SKI administrationsgebyr",
                            "percent", 2.0
                    )),
                    RuleBinding.of("FIXED_FEE", 30, Map.of(
                            "label", "Faktureringsgebyr",
                            "amount", 2000.0
                    ))
            );
        };
    }
}
