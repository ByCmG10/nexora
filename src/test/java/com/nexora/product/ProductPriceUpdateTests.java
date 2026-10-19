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
class ProductPriceUpdateTests {

    private static final String SKU = "PRICE-1";
    private static final String INITIAL_PRICE = "9.99";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void seedProducts() {
        productRepository.deleteAll();
        productRepository.save(new Product("Price Product", SKU, new BigDecimal(INITIAL_PRICE), 10, 3));
        productRepository.save(new Product("Bystander", "OTHER-1", new BigDecimal("1.00"), 1, 0));
    }

    private ResultActions updatePrice(String sku, String json) throws Exception {
        return mockMvc.perform(put("/api/products/sku/{sku}/price", sku)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private BigDecimal storedPrice() {
        return productRepository.findBySku(SKU).orElseThrow().getPrice();
    }

    private void assertPriceUnchanged() {
        assertThat(storedPrice()).isEqualByComparingTo(INITIAL_PRICE);
        assertThat(productRepository.findBySku("OTHER-1").orElseThrow().getPrice()).isEqualByComparingTo("1.00");
    }

    @Test
    @WithMockUser
    void validUpdateReturnsOkWithUpdatedProductAndPersistsIt() throws Exception {
        updatePrice(SKU, "{\"price\":44.99}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(44.99))
                .andExpect(jsonPath("$.sku").value(SKU))
                .andExpect(jsonPath("$.name").value("Price Product"))
                .andExpect(jsonPath("$.stock").value(10))
                .andExpect(jsonPath("$.minimumStock").value(3))
                .andExpect(jsonPath("$.id").isNumber());

        assertThat(storedPrice()).isEqualByComparingTo("44.99");
        assertThat(productRepository.count()).isEqualTo(2);
        assertThat(productRepository.findBySku("OTHER-1").orElseThrow().getPrice()).isEqualByComparingTo("1.00");
    }

    @Test
    @WithMockUser
    void onlyPriceIsUpdatedEvenIfOtherFieldsAreSent() throws Exception {
        updatePrice(SKU, "{\"price\":20,\"name\":\"Hacked\",\"stock\":999,\"minimumStock\":99,\"sku\":\"OTHER\"}")
                .andExpect(status().isOk());

        Product stored = productRepository.findBySku(SKU).orElseThrow();
        assertThat(stored.getPrice()).isEqualByComparingTo("20.00");
        assertThat(stored.getName()).isEqualTo("Price Product");
        assertThat(stored.getStock()).isEqualTo(10);
        assertThat(stored.getMinimumStock()).isEqualTo(3);
        assertThat(productRepository.existsBySku("OTHER")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "0.1", "1", "44.9", "44.90", "9999999999", "9999999999.99"})
    @WithMockUser
    void pricesGreaterThanZeroAndCompatibleWithTheColumnAreAcceptedAndPersisted(String price) throws Exception {
        updatePrice(SKU, "{\"price\":" + price + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(new BigDecimal(price).doubleValue()));

        assertThat(storedPrice()).isEqualByComparingTo(price);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "0.00", "-0.00", "-0.01", "-1", "-44.99", "-9999999999.99"})
    @WithMockUser
    void zeroOrNegativePriceIsRejectedAndProductIsNotModified(String price) throws Exception {
        updatePrice(SKU, "{\"price\":" + price + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").exists());

        assertPriceUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"price\":null}", "{}", "{\"other\":5}"})
    @WithMockUser
    void nullOrMissingPriceIsRejectedAndProductIsNotModified(String json) throws Exception {
        updatePrice(SKU, json)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").exists());

        assertPriceUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10000000000", "10000000000.00", "99999999999.99", "1.005", "0.001", "44.999"})
    @WithMockUser
    void pricesExceedingPrecisionOrScaleAreRejectedAndProductIsNotModified(String price) throws Exception {
        updatePrice(SKU, "{\"price\":" + price + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").exists());

        assertPriceUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"price\":\"abc\"}", "{\"price\":true}", "{\"price\":", "not json", ""})
    @WithMockUser
    void malformedOrNonNumericBodyIsRejectedAndProductIsNotModified(String json) throws Exception {
        updatePrice(SKU, json).andExpect(status().isBadRequest());

        assertPriceUnchanged();
    }

    @Test
    @WithMockUser
    void unknownSkuReturnsNotFoundAndCreatesNothing() throws Exception {
        updatePrice("NO-SUCH-SKU", "{\"price\":5.00}").andExpect(status().isNotFound());

        assertThat(productRepository.count()).isEqualTo(2);
        assertThat(productRepository.existsBySku("NO-SUCH-SKU")).isFalse();
        assertPriceUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"price-1", "Price-1", "  price-1  ", " PRICE-1"})
    @WithMockUser
    void skuInThePathIsNormalisedToLocateTheProduct(String sku) throws Exception {
        updatePrice(sku, "{\"price\":42.50}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value(SKU))
                .andExpect(jsonPath("$.price").value(42.50));

        assertThat(storedPrice()).isEqualByComparingTo("42.50");
        assertThat(productRepository.findBySku("OTHER-1").orElseThrow().getPrice()).isEqualByComparingTo("1.00");
    }

    @Test
    void unauthenticatedRequestIsRejectedAndDoesNotModifyThePrice() throws Exception {
        updatePrice(SKU, "{\"price\":44.99}").andExpect(status().isUnauthorized());

        assertPriceUnchanged();
    }

    @Test
    void wrongCredentialsAreRejectedAndDoNotModifyThePrice() throws Exception {
        mockMvc.perform(put("/api/products/sku/{sku}/price", SKU).with(httpBasic("nexora_test", "wrong"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\":44.99}"))
                .andExpect(status().isUnauthorized());

        assertPriceUnchanged();
    }

    @Test
    void httpBasicWithoutCsrfTokenUpdatesPrice() throws Exception {
        mockMvc.perform(put("/api/products/sku/{sku}/price", SKU)
                        .with(httpBasic("nexora_test", "nexora-test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\":44.99}"))
                .andExpect(status().isOk());

        assertThat(storedPrice()).isEqualByComparingTo("44.99");
    }
}
