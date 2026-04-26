package dk.trustworks.intranet.recruitmentservice.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class RecruitmentConfig {

    @ConfigProperty(name = "recruitment.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "recruitment.ai.enabled", defaultValue = "false")
    boolean aiEnabled;

    @ConfigProperty(name = "recruitment.outlook.enabled", defaultValue = "false")
    boolean outlookEnabled;

    @ConfigProperty(name = "recruitment.slack.enabled", defaultValue = "false")
    boolean slackEnabled;

    @ConfigProperty(name = "recruitment.nextsign.enabled", defaultValue = "false")
    boolean nextsignEnabled;

    @ConfigProperty(name = "recruitment.sharepoint.enabled", defaultValue = "false")
    boolean sharepointEnabled;

    @ConfigProperty(name = "recruitment.ai.cv-extraction.enabled", defaultValue = "false")
    boolean aiCvExtractionEnabled;

    @ConfigProperty(name = "recruitment.ai.role-brief.enabled", defaultValue = "false")
    boolean aiRoleBriefEnabled;

    @ConfigProperty(name = "recruitment.ai.candidate-summary.enabled", defaultValue = "false")
    boolean aiCandidateSummaryEnabled;

    @ConfigProperty(name = "recruitment.ai.interview-kit.enabled", defaultValue = "false")
    boolean aiInterviewKitEnabled;

    @ConfigProperty(name = "recruitment.ai.scorecard-roundup.enabled", defaultValue = "false")
    boolean aiScorecardRoundupEnabled;

    public boolean isEnabled() { return enabled; }
    public boolean aiEnabled() { return aiEnabled; }
    public boolean outlookEnabled() { return outlookEnabled; }
    public boolean slackEnabled() { return slackEnabled; }
    public boolean nextsignEnabled() { return nextsignEnabled; }
    public boolean sharepointEnabled() { return sharepointEnabled; }
    public boolean aiCvExtractionEnabled() { return aiCvExtractionEnabled; }
    public boolean aiRoleBriefEnabled() { return aiRoleBriefEnabled; }
    public boolean aiCandidateSummaryEnabled() { return aiCandidateSummaryEnabled; }
    public boolean aiInterviewKitEnabled() { return aiInterviewKitEnabled; }
    public boolean aiScorecardRoundupEnabled() { return aiScorecardRoundupEnabled; }
}
