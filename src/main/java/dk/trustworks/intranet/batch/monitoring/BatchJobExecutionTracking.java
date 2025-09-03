package dk.trustworks.intranet.batch.monitoring;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "batch_job_execution_tracking")
@Getter
@Setter
@NoArgsConstructor
public class BatchJobExecutionTracking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_name", nullable = false, length = 200)
    private String jobName;

    @Column(name = "execution_id", nullable = false, unique = true)
    private Long executionId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "exit_status", length = 128)
    private String exitStatus;

    @Column(name = "result", length = 16)
    private String result; // COMPLETED, FAILED, PARTIAL

    @Column(name = "progress_percent")
    private Integer progressPercent; // 0..100

    @Column(name = "total_subtasks")
    private Integer totalSubtasks;

    @Column(name = "completed_subtasks")
    private Integer completedSubtasks;

    @Lob
    @Column(name = "details")
    private String details;

    // In dk.trustworks.intranet.batch.monitoring.BatchJobExecutionTracking
    @Lob
    @jakarta.persistence.Column(name = "trace_log", columnDefinition = "MEDIUMTEXT")
    private String traceLog;


}
