package dk.trustworks.intranet.userservice.model.enums;

import lombok.Getter;

/**
 * Created by hans on 10/09/2017.
 */
@Getter
public enum StatusType {

    PREBOARDING(""), ACTIVE(""), TERMINATED(""), NON_PAY_LEAVE("db72d723-3073-44af-8fc1-db3027715b58"), MATERNITY_LEAVE("da2f89fc-9aef-4029-8ac2-7486be60e9b9"), PAID_LEAVE("7f3f739a-f4de-4242-b28c-d27d0e7b8c4a");

    private final String taskuuid;

    StatusType(String taskuuid) {
        this.taskuuid = taskuuid;
    }
}
