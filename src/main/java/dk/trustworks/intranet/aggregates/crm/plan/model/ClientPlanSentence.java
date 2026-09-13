package dk.trustworks.intranet.aggregates.crm.plan.model;

import dk.trustworks.intranet.aggregates.crm.plan.model.enums.PlanSlot;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One of the plan's four sentences: why this account matters, where we stand today, where
 * we want to be, and the open question.
 *
 * <p>The {@link #slot} decides the sentence's assertion kind — observation, target or
 * hypothesis — and nobody picks it. That is the whole mechanism: "we will own the
 * integration layer" sits in DESIRED and is therefore read as a target, so it can never be
 * mistaken for a description of today.
 *
 * <p>{@link #byUuid} is who WROTE the sentence, which is not the same as who last
 * confirmed the plan. Both matter and they are kept apart on purpose.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@IdClass(ClientPlanSentenceId.class)
@Table(name = "client_plan_sentence")
public class ClientPlanSentence extends PanacheEntityBase {

    @Id
    @Column(name = "client_uuid", length = 36)
    private String clientUuid;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "slot", length = 10)
    private PlanSlot slot;

    @Column(name = "sentence_text", length = 1000, nullable = false)
    private String text;

    @Column(name = "by_uuid", length = 36, nullable = false)
    private String byUuid;

    @Column(name = "validated_at", nullable = false)
    private LocalDateTime validatedAt;
}
