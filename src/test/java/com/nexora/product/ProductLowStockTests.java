package com.nexora.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs against the isolated in-memory database from src/test/resources/application.properties. */
@SpringBootTest
@AutoConfigureMockMvc
class ProductLowStockTests {

    private static final String URL = "/api/products/low-stock";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void cleanDatabase() {
        productRepository.deleteAll();
    }

    private Product save(String sku, int stock, int minimumStock) {
        return productRepository.save(new Product("Product " + sku, sku, new BigDecimal("9.99"), stock, minimumStock));
    }

    @Test
    @WithMockUser
    void productWithStockBelowMinimumIsReturned() throws Exception {
        save("BELOW", 2, 5);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("BELOW"))
                .andExpect(jsonPath("$[0].name").value("Product BELOW"))
                .andExpect(jsonPath("$[0].price").value(9.99))
                .andExpect(jsonPath("$[0].stock").value(2))
                .andExpect(jsonPath("$[0].minimumStock").value(5))
                .andExpect(jsonPath("$[0].id").isNumber());
    }

    @Test
    @WithMockUser
    void productWithStockEqualToMinimumIsReturned() throws Exception {
        save("EQUAL", 5, 5);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("EQUAL"));
    }

    @Test
    @WithMockUser
    void productWithStockAboveMinimumIsNotReturned() throws Exception {
        save("ABOVE", 6, 5);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void zeroStockAndZeroMinimumCountsAsLowStockButZeroMinimumWithStockDoesNot() throws Exception {
        save("ZERO-ZERO", 0, 0);
        save("ONE-ZERO", 1, 0);
        save("ZERO-FIVE", 0, 5);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("ZERO-ZERO", "ZERO-FIVE")));
    }

    @Test
    @WithMockUser
    void onlyProductsMeetingTheRuleAreReturnedFromAMix() throws Exception {
        save("A-BELOW", 1, 10);
        save("B-ABOVE", 11, 10);
        save("C-EQUAL", 10, 10);
        save("D-ABOVE", 1000, 0);
        save("E-BELOW", 0, 1);
        save("F-JUST-ABOVE", 3, 2);
        save("G-EQUAL-HIGH", 2147483647, 2147483647);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].sku", contains("A-BELOW", "C-EQUAL", "E-BELOW", "G-EQUAL-HIGH")));
    }

    @Test
    @WithMockUser
    void noLowStockProductsReturnsOkWithEmptyList() throws Exception {
        save("OK-1", 10, 5);
        save("OK-2", 6, 5);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void emptyCatalogReturnsOkWithEmptyList() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void lowStockPathIsNotTreatedAsASku() throws Exception {
        save("LOW-STOCK", 1, 5);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value("LOW-STOCK"));
        mockMvc.perform(get("/api/products/sku/LOW-STOCK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("LOW-STOCK"));
    }

    @Test
    @WithMockUser
    void queryDoesNotModifyProductsOrTheirData() throws Exception {
        save("LOW-1", 1, 5);
        save("LOW-2", 5, 5);
        save("FINE-1", 9, 5);
        List<String> before = snapshot();

        mockMvc.perform(get(URL)).andExpect(status().isOk());
        mockMvc.perform(get(URL)).andExpect(status().isOk());

        assertThat(snapshot()).isEqualTo(before);
        assertThat(productRepository.count()).isEqualTo(3);
    }

    private List<String> snapshot() {
        return productRepository.findAll().stream()
                .map(p -> p.getId() + "|" + p.getName() + "|" + p.getSku() + "|" + p.getPrice().toPlainString()
                        + "|" + p.getStock() + "|" + p.getMinimumStock())
                .sorted()
                .toList();
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        save("LOW-1", 1, 5);

        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        save("LOW-1", 1, 5);

        mockMvc.perform(get(URL).with(httpBasic("nexora_test", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicWithCorrectCredentialsCanQuery() throws Exception {
        save("LOW-1", 1, 5);

        mockMvc.perform(get(URL).with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value("LOW-1"));
    }
}
