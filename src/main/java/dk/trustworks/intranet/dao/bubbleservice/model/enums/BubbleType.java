package dk.trustworks.intranet.dao.bubbleservice.model.enums;

public enum BubbleType {
    KNOWLEDGE("b_"), SOCIAL("s_"), ACCOUNT_TEAM("a_"), FOCUS("f_");

    private final String slackPrefix;

    BubbleType(String slackPrefix) {
        this.slackPrefix = slackPrefix;
    }

    public String getSlackPrefix() {
        return slackPrefix;
    }
}
