package dk.trustworks.intranet.aggregates.invoice.rules;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class ContractRulesService {

    @Inject RuleRegistry ruleRegistry;
    @Inject DefaultRuleSets defaultRuleSets;
    @Inject ObjectMapper om;

    public List<RuleBinding> bindingsFor(Contract contract) {
        List<RuleBinding> result = new ArrayList<>(defaultRuleSets.forType(contract.getContractType()));
        // overlay with RULE:<CODE> items on the contract
        for (ContractTypeItem item : contract.getContractTypeItems()) {
            String key = item.getKey();
            if (key == null || !key.startsWith("RULE:")) continue;
            String code = key.substring("RULE:".length()).trim();
            Map<String,Object> params = parse(item.getValue());
            int order = ruleRegistry.find(code).map(ContractRule::order).orElse(100);
            upsert(result, new RuleBinding(code, order, params));
        }
        return result.stream()
                .filter(b -> ruleRegistry.find(b.code()).isPresent())
                .sorted(Comparator.comparingInt(RuleBinding::order))
                .collect(Collectors.toList());
    }

    private Map<String,Object> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return om.readValue(json, new TypeReference<Map<String,Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Bad rule params json: " + json, e);
        }
    }
    private static void upsert(List<RuleBinding> list, RuleBinding b) {
        for (int i=0;i<list.size();i++) if (list.get(i).code().equals(b.code())) { list.set(i,b); return; }
        list.add(b);
    }
}
