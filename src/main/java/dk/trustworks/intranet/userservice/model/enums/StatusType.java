package dk.trustworks.intranet.userservice.model.enums;

import lombok.Getter;

/**
 * Created by hans on 10/09/2017.
 */
@Getter
public enum StatusType {

    PREBOARDING("Ikke ansat", ""), ACTIVE("Active", ""), TERMINATED("Terminated", ""), NON_PAY_LEAVE("Ulønnet orlov","db72d723-3073-44af-8fc1-db3027715b58"), MATERNITY_LEAVE("Barselsorlov med løn","da2f89fc-9aef-4029-8ac2-7486be60e9b9"), PAID_LEAVE("Lønnet orlov","7f3f739a-f4de-4242-b28c-d27d0e7b8c4a");

    private final String danlonState;
    private final String taskuuid;

    StatusType(String danlon_state, String taskuuid) {
        danlonState = danlon_state;
        this.taskuuid = taskuuid;
    }
}
