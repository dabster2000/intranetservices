package dk.trustworks.intranet.financeservice.model.enums;

import java.util.List;

public enum CostSource {
    BOOKED(List.of(PostingStatus.BOOKED)),
    BOOKED_PLUS_DRAFT(List.of(PostingStatus.BOOKED, PostingStatus.DRAFT));

    private final List<PostingStatus> postingStatuses;

    CostSource(List<PostingStatus> postingStatuses) {
        this.postingStatuses = postingStatuses;
    }

    public List<PostingStatus> postingStatuses() {
        return postingStatuses;
    }

    public List<String> postingStatusNames() {
        return postingStatuses.stream().map(Enum::name).toList();
    }

    public static CostSource fromQueryParam(String value) {
        if (value == null || value.isBlank()) {
            return BOOKED;
        }
        try {
            return CostSource.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return BOOKED;
        }
    }
}
