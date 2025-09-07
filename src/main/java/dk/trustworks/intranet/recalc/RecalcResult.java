package dk.trustworks.intranet.recalc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class RecalcResult {
    private final RecalcTrigger trigger;
    private final String userUuid;
    private final LocalDate day;
    private final List<String> messages = new ArrayList<>();
    private boolean failed;

    public RecalcResult(RecalcTrigger trigger, String userUuid, LocalDate day) {
        this.trigger = trigger;
        this.userUuid = userUuid;
        this.day = day;
    }

    public void merge(StageResult r) {
        if (r == null) return;
        messages.add(r.summary());
        if (r.error().isPresent()) failed = true;
    }

    public boolean isFailed() { return failed; }

    public String summary() { return String.join("; ", messages); }

    public RecalcTrigger trigger() { return trigger; }
    public String userUuid() { return userUuid; }
    public LocalDate day() { return day; }
}
