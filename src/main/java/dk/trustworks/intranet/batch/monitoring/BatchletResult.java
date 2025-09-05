package dk.trustworks.intranet.batch.monitoring;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchletResult implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    public enum Status {
        SUCCESS,
        FAILURE,
        PARTIAL
    }
    
    private Status status = Status.SUCCESS;
    private String message;
    private String errorDetails;
    private Throwable exception;
    private long processingTimeMs;
    private String partitionId;
    
    public static BatchletResult success() {
        return new BatchletResult(Status.SUCCESS, null, null, null, 0, null);
    }
    
    public static BatchletResult success(String message) {
        return new BatchletResult(Status.SUCCESS, message, null, null, 0, null);
    }
    
    public static BatchletResult failure(String message) {
        return new BatchletResult(Status.FAILURE, message, null, null, 0, null);
    }
    
    public static BatchletResult failure(String message, Throwable exception) {
        return new BatchletResult(Status.FAILURE, message, exception != null ? exception.getMessage() : null, exception, 0, null);
    }
    
    public static BatchletResult partial(String message) {
        return new BatchletResult(Status.PARTIAL, message, null, null, 0, null);
    }
    
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
    
    public boolean isFailure() {
        return status == Status.FAILURE;
    }
    
    public boolean isPartial() {
        return status == Status.PARTIAL;
    }
}