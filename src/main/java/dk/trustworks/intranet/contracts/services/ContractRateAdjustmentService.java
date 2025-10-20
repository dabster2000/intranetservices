package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.dto.CreateRateAdjustmentRequest;
import dk.trustworks.intranet.contracts.dto.RateAdjustmentDTO;
import dk.trustworks.intranet.contracts.dto.UpdateRateAdjustmentRequest;
import dk.trustworks.intranet.contracts.model.ContractRateAdjustmentEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@JBossLog
@ApplicationScoped
public class ContractRateAdjustmentService {

    @Transactional
    public RateAdjustmentDTO create(String contractTypeCode, CreateRateAdjustmentRequest request) {
        log.info("ContractRateAdjustmentService.create");
        log.info("contractTypeCode = " + contractTypeCode + ", request = " + request);

        if (ContractRateAdjustmentEntity.existsByContractTypeAndRuleId(contractTypeCode, request.getRuleId())) {
            throw new BadRequestException("Rate adjustment with ID '" + request.getRuleId() +
                    "' already exists for contract type '" + contractTypeCode + "'");
        }

        // Validate date range
        if (request.getEndDate() != null && !request.getEffectiveDate().isBefore(request.getEndDate())) {
            throw new BadRequestException("effective_date must be before end_date");
        }

        ContractRateAdjustmentEntity entity = new ContractRateAdjustmentEntity();
        entity.setContractTypeCode(contractTypeCode);
        entity.setRuleId(request.getRuleId());
        entity.setLabel(request.getLabel());
        entity.setAdjustmentType(request.getAdjustmentType());
        entity.setAdjustmentPercent(request.getAdjustmentPercent());
        entity.setFrequency(request.getFrequency());
        entity.setEffectiveDate(request.getEffectiveDate());
        entity.setEndDate(request.getEndDate());
        entity.setPriority(request.getPriority());
        entity.setActive(request.isActive());
        entity.persist();

        log.info("Created rate adjustment: " + entity.getRuleId());
        return RateAdjustmentDTO.fromEntity(entity);
    }

    @Transactional
    public RateAdjustmentDTO update(String contractTypeCode, String ruleId, UpdateRateAdjustmentRequest request) {
        log.info("ContractRateAdjustmentService.update");

        ContractRateAdjustmentEntity entity = ContractRateAdjustmentEntity.findByContractTypeAndRuleId(contractTypeCode, ruleId);
        if (entity == null) {
            throw new NotFoundException("Rate adjustment '" + ruleId + "' not found");
        }

        if (request.getEndDate() != null && !request.getEffectiveDate().isBefore(request.getEndDate())) {
            throw new BadRequestException("effective_date must be before end_date");
        }

        entity.setLabel(request.getLabel());
        entity.setAdjustmentType(request.getAdjustmentType());
        entity.setAdjustmentPercent(request.getAdjustmentPercent());
        entity.setFrequency(request.getFrequency());
        entity.setEffectiveDate(request.getEffectiveDate());
        entity.setEndDate(request.getEndDate());
        entity.setPriority(request.getPriority());
        entity.setActive(request.isActive());
        entity.persist();

        return RateAdjustmentDTO.fromEntity(entity);
    }

    @Transactional
    public void softDelete(String contractTypeCode, String ruleId) {
        ContractRateAdjustmentEntity entity = ContractRateAdjustmentEntity.findByContractTypeAndRuleId(contractTypeCode, ruleId);
        if (entity == null) {
            throw new NotFoundException("Rate adjustment '" + ruleId + "' not found");
        }
        entity.softDelete();
    }

    public RateAdjustmentDTO findByRuleId(String contractTypeCode, String ruleId) {
        ContractRateAdjustmentEntity entity = ContractRateAdjustmentEntity.findByContractTypeAndRuleId(contractTypeCode, ruleId);
        if (entity == null) {
            throw new NotFoundException("Rate adjustment '" + ruleId + "' not found");
        }
        return RateAdjustmentDTO.fromEntity(entity);
    }

    public List<RateAdjustmentDTO> listAll(String contractTypeCode, boolean includeInactive) {
        List<ContractRateAdjustmentEntity> entities = includeInactive
                ? ContractRateAdjustmentEntity.findByContractTypeIncludingInactive(contractTypeCode)
                : ContractRateAdjustmentEntity.findByContractType(contractTypeCode);

        return entities.stream()
                .map(RateAdjustmentDTO::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Calculate adjusted rate based on active adjustments.
     */
    public BigDecimal getAdjustedRate(String contractTypeCode, BigDecimal baseRate, LocalDate effectiveDate) {
        List<ContractRateAdjustmentEntity> adjustments =
                ContractRateAdjustmentEntity.findByContractTypeAndDate(contractTypeCode, effectiveDate);

        BigDecimal adjustedRate = baseRate;
        for (ContractRateAdjustmentEntity adjustment : adjustments) {
            if (adjustment.getAdjustmentPercent() != null) {
                BigDecimal multiplier = BigDecimal.ONE.add(adjustment.getAdjustmentPercent().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
                adjustedRate = adjustedRate.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
            }
        }
        return adjustedRate;
    }
}
