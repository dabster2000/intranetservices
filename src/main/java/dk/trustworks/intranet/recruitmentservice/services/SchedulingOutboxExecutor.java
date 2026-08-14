package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingOutbox;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;

/**
 * One registered external-write executor of the Method B scheduling
 * outbox (plan §8.3). CDI beans implementing this interface register by
 * {@link #action()}; {@code RecruitmentSchedulingOutboxDispatcher}
 * routes claimed rows to them.
 * <p>
 * Contract:
 * <ul>
 *   <li>{@link #execute} runs OUTSIDE any transaction (the external
 *       call must not pin a JDBC connection); bookkeeping it needs goes
 *       through its own short transactions.</li>
 *   <li>It must be replay-safe: a crash between the external call and
 *       the COMPLETED mark re-runs it after the claim timeout. Graph
 *       creates carry {@code transactionId}; deletes treat 404 as done;
 *       Slack sends tolerate the rare duplicate.</li>
 *   <li>Throwing = the attempt failed; the dispatcher applies backoff
 *       and dead-letters after the cap. Returning normally = done.</li>
 * </ul>
 */
public interface SchedulingOutboxExecutor {

    /** The action this executor owns. */
    SchedulingOutboxAction action();

    /** Perform the external write for one claimed row. */
    void execute(RecruitmentSchedulingOutbox row) throws Exception;
}
