package com.nexora.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs against the isolated in-memory database from src/test/resources/application.properties. */
@SpringBootTest
@AutoConfigureMockMvc
class ProductOutOfStockTests {

    private static final String URL = "/api/products/out-of-stock";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void cleanDatabase() {
        productRepository.deleteAll();
    }

    private void save(String sku, int stock, int minimumStock) {
        productRepository.save(new Product("Product " + sku, sku, new BigDecimal("9.99"), stock, minimumStock));
    }

    @Test
    @WithMockUser
    void productWithZeroStockIsReturnedWithAllItsFields() throws Exception {
        save("EMPTY", 0, 5);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("EMPTY"))
                .andExpect(jsonPath("$[0].name").value("Product EMPTY"))
                .andExpect(jsonPath("$[0].price").value(9.99))
                .andExpect(jsonPath("$[0].stock").value(0))
                .andExpect(jsonPath("$[0].minimumStock").value(5))
                .andExpect(jsonPath("$[0].id").isNumber());
    }

    @Test
    @WithMockUser
    void onlyProductsWithStockExactlyZeroAreReturned() throws Exception {
        save("A-ZERO", 0, 0);
        save("B-ONE", 1, 0);
        save("C-LOW", 2, 10);
        save("D-ZERO", 0, 3);
        save("E-HIGH", 2147483647, 0);
        save("F-EQUAL-MIN", 5, 5);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].sku", contains("A-ZERO", "D-ZERO")))
                .andExpect(jsonPath("$[*].stock", contains(0, 0)));
    }

    @Test
    @WithMockUser
    void positiveStockIsExcludedEvenWhenBelowTheMinimum() throws Exception {
        save("LOW-BUT-NOT-EMPTY", 1, 100);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void severalOutOfStockProductsAreAllReturned() throws Exception {
        save("OUT-1", 0, 1);
        save("OUT-2", 0, 2);
        save("OUT-3", 0, 0);
        save("OUT-4", 0, 9);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].sku", contains("OUT-1", "OUT-2", "OUT-3", "OUT-4")));
    }

    @Test
    @WithMockUser
    void noOutOfStockProductsReturnsOkWithEmptyList() throws Exception {
        save("IN-1", 1, 0);
        save("IN-2", 50, 10);

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
    void queryDoesNotModifyProductsOrTheirData() throws Exception {
        save("ZERO-1", 0, 5);
        save("ZERO-2", 0, 0);
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

    // --- routing --------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void outOfStockPathIsNotTreatedAsASkuAndOtherGetsStillWork() throws Exception {
        save("OUT-OF-STOCK", 0, 1);
        save("LOW-1", 1, 5);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("OUT-OF-STOCK"));
        mockMvc.perform(get("/api/products/sku/OUT-OF-STOCK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("OUT-OF-STOCK"));
        mockMvc.perform(get("/api/products/low-stock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("OUT-OF-STOCK", "LOW-1")));
        mockMvc.perform(get("/api/products/search").param("name", "low"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @WithMockUser
    void onlyGetIsSupportedOnTheEndpointAndOtherMethodsDoNotChangeData() throws Exception {
        save("ZERO-1", 0, 5);
        List<String> before = snapshot();

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content("{\"stock\":5}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(URL)).andExpect(status().isMethodNotAllowed());

        assertThat(snapshot()).isEqualTo(before);
    }

    // --- security -------------------------------------------------------------------------------

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        save("ZERO-1", 0, 5);

        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        save("ZERO-1", 0, 5);

        mockMvc.perform(get(URL).with(httpBasic("nexora_test", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicWithCorrectCredentialsCanQuery() throws Exception {
        save("ZERO-1", 0, 5);

        mockMvc.perform(get(URL).with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value("ZERO-1"));
    }
}
