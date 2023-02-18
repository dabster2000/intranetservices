package dk.trustworks.intranet.knowledgeservice.model.enums;

public enum CKOExpenseStatus {

    WISHLIST("Wish list"), BOOKED("Booked"), COMPLETED("Completed");

    private final String caption;

    CKOExpenseStatus(String caption) {
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
