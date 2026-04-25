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

    public boolean isEnabled() { return enabled; }
    public boolean aiEnabled() { return aiEnabled; }
    public boolean outlookEnabled() { return outlookEnabled; }
    public boolean slackEnabled() { return slackEnabled; }
    public boolean nextsignEnabled() { return nextsignEnabled; }
    public boolean sharepointEnabled() { return sharepointEnabled; }
}
