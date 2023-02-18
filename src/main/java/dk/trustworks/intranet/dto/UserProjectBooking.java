package dk.trustworks.intranet.dto;

public class UserProjectBooking extends UserBooking {

    public UserProjectBooking() {
    }

    public UserProjectBooking(String username, String uuid, int i, boolean parent) {
        super(username, uuid, i, parent);
    }

    @Override
    public int getHoursDone() {
        return super.getHoursDone();
    }
}
