package dk.trustworks.intranet.knowledgeservice.model.enums;

public enum CKOExpensePurpose {

    NEW("New skill"), IMPROVE("Improve skill"), PASSION("Its my passion"), CUSTOMER("Customer need");

    private final String caption;

    CKOExpensePurpose(String caption) {
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
