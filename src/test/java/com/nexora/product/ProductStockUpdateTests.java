package com.nexora.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs against the isolated in-memory database from src/test/resources/application.properties. */
@SpringBootTest
@AutoConfigureMockMvc
class ProductStockUpdateTests {

    private static final String SKU = "STOCK-1";
    private static final int INITIAL_STOCK = 10;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void seedProduct() {
        productRepository.deleteAll();
        productRepository.save(new Product("Stock Product", SKU, new BigDecimal("9.99"), INITIAL_STOCK, 3));
    }

    private ResultActions updateStock(String sku, String json) throws Exception {
        return mockMvc.perform(put("/api/products/sku/{sku}/stock", sku)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private int storedStock() {
        return productRepository.findBySku(SKU).orElseThrow().getStock();
    }

    @Test
    @WithMockUser
    void validUpdateReturnsOkWithUpdatedProductAndPersistsIt() throws Exception {
        updateStock(SKU, "{\"stock\":25}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(25))
                .andExpect(jsonPath("$.sku").value(SKU))
                .andExpect(jsonPath("$.name").value("Stock Product"))
                .andExpect(jsonPath("$.price").value(9.99))
                .andExpect(jsonPath("$.minimumStock").value(3))
                .andExpect(jsonPath("$.id").isNumber());

        assertThat(storedStock()).isEqualTo(25);
        assertThat(productRepository.count()).isEqualTo(1);
    }

    @Test
    @WithMockUser
    void zeroStockIsValid() throws Exception {
        updateStock(SKU, "{\"stock\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(0));

        assertThat(storedStock()).isZero();
    }

    @Test
    @WithMockUser
    void onlyStockIsUpdatedEvenIfOtherFieldsAreSent() throws Exception {
        updateStock(SKU, "{\"stock\":7,\"name\":\"Hacked\",\"price\":1.00,\"minimumStock\":99,\"sku\":\"OTHER\"}")
                .andExpect(status().isOk());

        Product stored = productRepository.findBySku(SKU).orElseThrow();
        assertThat(stored.getStock()).isEqualTo(7);
        assertThat(stored.getName()).isEqualTo("Stock Product");
        assertThat(stored.getPrice()).isEqualByComparingTo("9.99");
        assertThat(stored.getMinimumStock()).isEqualTo(3);
        assertThat(productRepository.existsBySku("OTHER")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-100", "-2147483648"})
    @WithMockUser
    void negativeStockIsRejectedAndPreviousStockIsKept(String stock) throws Exception {
        updateStock(SKU, "{\"stock\":" + stock + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.stock").exists());

        assertThat(storedStock()).isEqualTo(INITIAL_STOCK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"stock\":null}", "{}", "{\"other\":5}"})
    @WithMockUser
    void nullOrMissingStockIsRejectedAndPreviousStockIsKept(String json) throws Exception {
        updateStock(SKU, json)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.stock").exists());

        assertThat(storedStock()).isEqualTo(INITIAL_STOCK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"stock\":\"abc\"}", "{\"stock\":", "not json", "", "{\"stock\":99999999999}"})
    @WithMockUser
    void malformedOrNonIntegerBodyIsRejectedAndPreviousStockIsKept(String json) throws Exception {
        updateStock(SKU, json).andExpect(status().isBadRequest());

        assertThat(storedStock()).isEqualTo(INITIAL_STOCK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"25.7", "-25.7", "0.5", "-0.5", "25.0", "25.00", "1e2", "2.5E0", "0.0", "-0.0"})
    @WithMockUser
    void decimalStockIsRejectedInsteadOfTruncatedAndPreviousStockIsKept(String stock) throws Exception {
        updateStock(SKU, "{\"stock\":" + stock + "}").andExpect(status().isBadRequest());

        assertThat(storedStock()).isEqualTo(INITIAL_STOCK);
    }

    @Test
    @WithMockUser
    void integerStockStillAcceptedAfterStrictDecimalHandling() throws Exception {
        updateStock(SKU, "{\"stock\":2147483647}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(2147483647));

        assertThat(storedStock()).isEqualTo(2147483647);
    }

    @Test
    @WithMockUser
    void unknownSkuReturnsNotFoundAndCreatesNothing() throws Exception {
        updateStock("NO-SUCH-SKU", "{\"stock\":5}").andExpect(status().isNotFound());

        assertThat(productRepository.count()).isEqualTo(1);
        assertThat(storedStock()).isEqualTo(INITIAL_STOCK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stock-1", "Stock-1", "  stock-1  ", " STOCK-1"})
    @WithMockUser
    void skuInThePathIsNormalisedToLocateTheProduct(String sku) throws Exception {
        updateStock(sku, "{\"stock\":42}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value(SKU))
                .andExpect(jsonPath("$.stock").value(42));

        assertThat(storedStock()).isEqualTo(42);
    }

    @Test
    void unauthenticatedRequestIsRejectedAndDoesNotModifyTheProduct() throws Exception {
        updateStock(SKU, "{\"stock\":25}").andExpect(status().isUnauthorized());

        assertThat(storedStock()).isEqualTo(INITIAL_STOCK);
    }

    @Test
    void wrongCredentialsAreRejectedAndDoNotModifyTheProduct() throws Exception {
        mockMvc.perform(put("/api/products/sku/{sku}/stock", SKU).with(httpBasic("nexora_test", "wrong"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stock\":25}"))
                .andExpect(status().isUnauthorized());

        assertThat(storedStock()).isEqualTo(INITIAL_STOCK);
    }

    @Test
    void httpBasicWithoutCsrfTokenUpdatesStock() throws Exception {
        mockMvc.perform(put("/api/products/sku/{sku}/stock", SKU)
                        .with(httpBasic("nexora_test", "nexora-test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stock\":25}"))
                .andExpect(status().isOk());

        assertThat(storedStock()).isEqualTo(25);
    }
}
