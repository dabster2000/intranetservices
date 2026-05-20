package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.ContraAccount;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.ExpenseAccount;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.FinanceVoucher;
import dk.trustworks.intranet.expenseservice.remote.dto.economics.VatAccount;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EconomicsExpenseVatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void finance_voucher_serializes_vat_account_when_set() throws Exception {
        FinanceVoucher voucher = new FinanceVoucher(
                new ExpenseAccount(3585),
                "Udlæg | ext | Telefon",
                250.00,
                new ContraAccount(24649),
                "2026-05-16");
        voucher.setVatAccount(new VatAccount("I25"));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(voucher));

        assertEquals("I25", json.path("vatAccount").path("vatCode").asText());
    }

    @Test
    void finance_voucher_omits_vat_account_when_unset() throws Exception {
        FinanceVoucher voucher = new FinanceVoucher(
                new ExpenseAccount(3585),
                "Udlæg | ext | Telefon",
                250.00,
                new ContraAccount(24649),
                "2026-05-16");

        JsonNode json = mapper.readTree(mapper.writeValueAsString(voucher));

        assertFalse(json.has("vatAccount"));
    }

    @Test
    void extracts_default_vat_code_from_account_json() {
        Optional<String> vatCode = EconomicsService.extractDefaultVatCode("""
                {
                  "accountNumber": 3585,
                  "name": "Telefon",
                  "vatAccount": { "vatCode": "I25" }
                }
                """);

        assertEquals(Optional.of("I25"), vatCode);
    }

    @Test
    void missing_or_blank_default_vat_code_returns_empty() {
        assertEquals(Optional.empty(), EconomicsService.extractDefaultVatCode(null));
        assertEquals(Optional.empty(), EconomicsService.extractDefaultVatCode(""));
        assertEquals(Optional.empty(), EconomicsService.extractDefaultVatCode("{\"accountNumber\":3585}"));
        assertEquals(Optional.empty(), EconomicsService.extractDefaultVatCode("{\"vatAccount\":null}"));
        assertEquals(Optional.empty(), EconomicsService.extractDefaultVatCode("{\"vatAccount\":{\"vatCode\":\"   \"}}"));
        assertEquals(Optional.empty(), EconomicsService.extractDefaultVatCode("not-json"));
    }

    @Test
    void build_finance_voucher_sets_vat_account_and_keeps_gross_amount() {
        EconomicsService service = new EconomicsService();
        Expense expense = new Expense();
        expense.setAccount("3585");
        expense.setAmount(250.00);
        UserAccount userAccount = new UserAccount(24649, "ext_2ff78121");

        FinanceVoucher voucher = service.buildFinanceVoucher(
                expense,
                userAccount,
                "Udlæg | ext_2ff78121 | Telefon",
                "2026-05-16",
                " I25 ");

        assertEquals(3585, voucher.getAccount().getAccountNumber());
        assertEquals(24649, voucher.getContraAccount().getAccountNumber());
        assertEquals(250.00, voucher.getAmount());
        assertNotNull(voucher.getVatAccount());
        assertEquals("I25", voucher.getVatAccount().getVatCode());
    }
}
