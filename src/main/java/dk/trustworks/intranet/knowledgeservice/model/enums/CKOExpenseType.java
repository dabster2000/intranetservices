package dk.trustworks.intranet.knowledgeservice.model.enums;

public enum CKOExpenseType {

    CONFERENCE("Conference"), COURSE("Course"), SUBSCRIPTION("Subscription"), MEMBERSHIP("Membership"), BOOKS("Books, etc.");

    private final String caption;

    CKOExpenseType(String caption) {
        this.caption = caption;
    }

    public String getCaption() {
        return caption;
    }

    @Override
    public String toString() {
        return this.caption;
    }
}
