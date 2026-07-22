package dk.trustworks.intranet.recruitmentservice.events;

/**
 * Row-level visibility of a recruitment event (spec §3.3 envelope).
 */
public enum RecruitmentEventVisibility {
    /** Visible per the ordinary involvement/scope rules. */
    NORMAL,
    /**
     * Partner-track ("circle") content: filtered per viewer everywhere,
     * and never posted to shared Slack channels (Slack spec §5.2).
     */
    CIRCLE
}
