package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.invoice.services.RegisteredDeliveryEvidenceResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PracticeDeliveryEvidenceRecoveryServiceTest {

    @Test
    void exactItemInventoryTreatsEveryUnlineagedHistoricalItemAsExplicitAbsence(){
        LocalDate date=LocalDate.of(2026,1,15);
        RegisteredDeliveryEvidenceResolver.ResolvedDelivery resolved=resolved(date);
        var itemOne=new PracticeDeliveryEvidenceRecoveryService.ItemInventory(
                "doc-1","item-1",decimal("2.000000"),decimal("100.000000"),"WORK","rule-1");
        var itemTwo=new PracticeDeliveryEvidenceRecoveryService.ItemInventory(
                "doc-1","item-2",decimal("1.000000"),decimal("50.000000"),"MANUAL",null);
        PracticeRevenueMaterializationService.StoredDelivery stored=stored(resolved,itemOne,"","");
        String distribution=PracticeRevenueMaterializationService.deliveryDistributionFingerprint(List.of(stored));
        String itemFingerprint=PracticeRevenueMaterializationService.deliveryItemFingerprint(stored,distribution);
        var lineage=new PracticeDeliveryEvidenceRecoveryService.LineageRow(
                "doc-1","item-1",resolved.workUuid(),resolved.registrantUuid(),
                resolved.effectiveConsultantUuid(),resolved.deliveryDate(),resolved.taskUuid(),
                resolved.projectUuid(),resolved.contractUuid(),resolved.contractProjectUuid(),
                resolved.contractConsultantUuid(),resolved.normalizedDuration(),resolved.normalizedRate(),
                resolved.deliveryValue(),"RESOLVED","PRACTICE_DELIVERY_LINEAGE_V1",itemFingerprint,
                distribution,itemOne.itemHours(),itemOne.itemRate(),itemOne.itemOrigin(),itemOne.itemRuleId());

        var result=PracticeDeliveryEvidenceRecoveryService.validateImmutableLineage(List.of(lineage),
                Map.of("item-1",itemOne,"item-2",itemTwo),Map.of("doc-1",List.of(resolved)));

        assertEquals(2,result.scopedItemCount());
        assertEquals(1,result.lineagedItemCount());
        assertEquals(1,result.explicitUnlineagedItemCount());
    }

    @Test
    void immutableLineageMustRemainByteIdenticalAndConserveTheOwningItem(){
        LocalDate date=LocalDate.of(2026,1,15);
        RegisteredDeliveryEvidenceResolver.ResolvedDelivery resolved=resolved(date);
        var item=new PracticeDeliveryEvidenceRecoveryService.ItemInventory(
                "doc-1","item-1",decimal("2.000000"),decimal("100.000000"),"WORK","rule-1");
        PracticeRevenueMaterializationService.StoredDelivery stored=stored(resolved,item,"","");
        String distribution=PracticeRevenueMaterializationService.deliveryDistributionFingerprint(List.of(stored));
        String itemFingerprint=PracticeRevenueMaterializationService.deliveryItemFingerprint(stored,distribution);
        var changed=new PracticeDeliveryEvidenceRecoveryService.LineageRow(
                "doc-1","item-1",resolved.workUuid(),resolved.registrantUuid(),
                resolved.effectiveConsultantUuid(),resolved.deliveryDate(),resolved.taskUuid(),
                resolved.projectUuid(),resolved.contractUuid(),resolved.contractProjectUuid(),
                resolved.contractConsultantUuid(),decimal("2.000000"),decimal("101.000000"),
                decimal("202.000000000000"),"RESOLVED","PRACTICE_DELIVERY_LINEAGE_V1",
                itemFingerprint,distribution,item.itemHours(),item.itemRate(),item.itemOrigin(),item.itemRuleId());

        IllegalStateException failure=assertThrows(IllegalStateException.class,
                ()->PracticeDeliveryEvidenceRecoveryService.validateImmutableLineage(List.of(changed),
                        Map.of("item-1",item),Map.of("doc-1",List.of(resolved))));
        assertEquals("DELIVERY_LINEAGE_CURRENT_MISMATCH",failure.getMessage());
    }

    @Test
    void scopeIsDirectRecognizedInvoicePlusExactOneHopCreditSourceWithoutHistoricalFabrication(){
        String normalized=(PracticeDeliveryEvidenceRecoveryService.SCOPE_SQL+"\n"
                +PracticeDeliveryEvidenceRecoveryService.ITEM_INVENTORY_SQL+"\n"
                +PracticeDeliveryEvidenceRecoveryService.LINEAGE_SQL).toLowerCase();
        assertTrue(normalized.contains("practice_basis_dependency_manifest_mat"));
        assertTrue(normalized.contains("recognized_document_type='credit_note'"));
        assertTrue(normalized.contains("source_document_uuid"));
        assertTrue(normalized.contains("type='invoice'"));
        assertTrue(normalized.contains("invoicedate>=:fromdate"));
        assertTrue(normalized.contains("practice_invoice_item_delivery_source"));
        assertTrue(!normalized.contains("description like")&&!normalized.contains("label like"));
        assertTrue(!normalized.contains("insert into fact_practice_net_revenue")
                &&!normalized.contains("update practice_revenue_publication"));
    }

    @Test
    void postTargetScanAdvancesOnlyAcrossIrrelevantEventsAndAbortsOnDeliveryMutation(){
        EntityManager em=mock(EntityManager.class);
        Query query=query();
        when(em.createNativeQuery(PracticeDeliveryEvidenceRecoveryService.FACT_EVENTS_SQL)).thenReturn(query);
        when(query.getResultList()).thenReturn(java.util.Collections.singletonList(
                new Object[]{BigInteger.valueOf(11),"OTHER","x","1"}));
        PracticeDeliveryEvidenceRecoveryService service=new PracticeDeliveryEvidenceRecoveryService();
        service.em=em;service.queryTimeout=Duration.ofMinutes(2);
        assertEquals(BigInteger.valueOf(11),service.scanPostTarget(BigInteger.TEN));

        when(query.getResultList()).thenReturn(java.util.Collections.singletonList(
                new Object[]{BigInteger.valueOf(12),"WORK","work","w"}));
        assertEquals("DELIVERY_RELEVANT_EVENT_AFTER_TARGET",assertThrows(IllegalStateException.class,
                ()->service.scanPostTarget(BigInteger.TEN)).getMessage());
    }

    private static RegisteredDeliveryEvidenceResolver.ResolvedDelivery resolved(LocalDate date){
        return new RegisteredDeliveryEvidenceResolver.ResolvedDelivery("work-1","registrant-1","consultant-1",
                date,"task-1","project-1","contract-1","contract-project-1","contract-consultant-1",
                decimal("2.000000"),decimal("100.000000"),decimal("200.000000000000"),
                RegisteredDeliveryEvidenceResolver.RateResolutionStatus.RESOLVED);
    }

    private static PracticeRevenueMaterializationService.StoredDelivery stored(
            RegisteredDeliveryEvidenceResolver.ResolvedDelivery row,
            PracticeDeliveryEvidenceRecoveryService.ItemInventory item,String itemFingerprint,
            String distributionFingerprint){
        return new PracticeRevenueMaterializationService.StoredDelivery(item.invoiceItemUuid(),row.workUuid(),
                row.registrantUuid(),row.effectiveConsultantUuid(),row.deliveryDate(),row.taskUuid(),
                row.projectUuid(),row.contractUuid(),row.contractProjectUuid(),row.contractConsultantUuid(),
                row.normalizedDuration(),row.normalizedRate(),row.deliveryValue(),"RESOLVED",
                "PRACTICE_DELIVERY_LINEAGE_V1",itemFingerprint,distributionFingerprint,item.itemHours(),
                item.itemRate(),item.itemOrigin(),item.itemRuleId(),null,null,null);
    }

    private static BigDecimal decimal(String value){return new BigDecimal(value);}

    private static Query query(){
        Query query=mock(Query.class);
        when(query.setHint(anyString(),any())).thenReturn(query);
        when(query.setParameter(anyString(),any())).thenReturn(query);
        return query;
    }
}
