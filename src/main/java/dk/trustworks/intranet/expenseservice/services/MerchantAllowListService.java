package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.dto.AddAllowListEntryRequest;
import dk.trustworks.intranet.expenseservice.dto.MerchantAllowListEntryDTO;
import dk.trustworks.intranet.expenseservice.model.MerchantAllowList;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;

@ApplicationScoped
public class MerchantAllowListService {

    @Inject
    RequestHeaderHolder headers;

    public List<MerchantAllowListEntryDTO> list() {
        return MerchantAllowList.<MerchantAllowList>listAll().stream()
            .map(e -> new MerchantAllowListEntryDTO(
                e.uuid, e.ruleId, e.merchantNamePattern, e.matchKind, e.notes, e.addedByUuid, e.createdAt))
            .toList();
    }

    @Transactional
    public MerchantAllowListEntryDTO add(AddAllowListEntryRequest req) {
        MerchantAllowList e = new MerchantAllowList();
        e.ruleId              = req.ruleId();
        e.merchantNamePattern = req.merchantNamePattern();
        e.matchKind           = req.matchKind() == null ? "CONTAINS" : req.matchKind();
        e.notes               = req.notes();
        e.addedByUuid         = headers.getUserUuid();
        e.persist();
        return new MerchantAllowListEntryDTO(
            e.uuid, e.ruleId, e.merchantNamePattern, e.matchKind, e.notes, e.addedByUuid, e.createdAt);
    }

    @Transactional
    public boolean delete(String uuid) {
        return MerchantAllowList.deleteById(uuid);
    }

    public boolean matches(String ruleId, String merchant) {
        if (merchant == null) return false;
        String needle = merchant.toLowerCase();
        return MerchantAllowList.<MerchantAllowList>find("ruleId", ruleId)
            .stream()
            .anyMatch(e -> {
                String p = e.merchantNamePattern.toLowerCase();
                return "EXACT".equals(e.matchKind) ? needle.equals(p) : needle.contains(p);
            });
    }
}
