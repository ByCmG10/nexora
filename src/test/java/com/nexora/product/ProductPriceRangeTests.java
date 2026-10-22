package com.nexora.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
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
class ProductPriceRangeTests {

    private static final String URL = "/api/products/by-price-range";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void seedProducts() {
        productRepository.deleteAll();
        save("P-1999", "19.99");
        save("P-2000", "20.00");
        save("P-3500", "35.00");
        save("P-5000", "50.00");
        save("P-5001", "50.01");
    }

    private void save(String sku, String price) {
        productRepository.save(new Product("Product " + sku, sku, new BigDecimal(price), 10, 2));
    }

    private ResultActions range(String min, String max) throws Exception {
        return mockMvc.perform(get(URL).param("min", min).param("max", max));
    }

    // --- matching -------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void productsWithinTheRangeAreReturnedAndOthersExcluded() throws Exception {
        range("20.00", "50.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].sku", contains("P-2000", "P-3500", "P-5000")))
                .andExpect(jsonPath("$[*].price", contains(20.00, 35.00, 50.00)));
    }

    @Test
    @WithMockUser
    void returnedProductsIncludeAllTheirFields() throws Exception {
        range("35.00", "35.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("P-3500"))
                .andExpect(jsonPath("$[0].name").value("Product P-3500"))
                .andExpect(jsonPath("$[0].price").value(35.00))
                .andExpect(jsonPath("$[0].stock").value(10))
                .andExpect(jsonPath("$[0].minimumStock").value(2))
                .andExpect(jsonPath("$[0].id").isNumber());
    }

    @Test
    @WithMockUser
    void productEqualToMinIsIncluded() throws Exception {
        range("20.00", "30.00").andExpect(jsonPath("$[*].sku", contains("P-2000")));
    }

    @Test
    @WithMockUser
    void productEqualToMaxIsIncluded() throws Exception {
        range("30.00", "50.00").andExpect(jsonPath("$[*].sku", contains("P-3500", "P-5000")));
    }

    @Test
    @WithMockUser
    void productsBelowMinAreExcluded() throws Exception {
        range("20.00", "50.00").andExpect(jsonPath("$[?(@.sku == 'P-1999')]").isEmpty());
        range("20.01", "50.00").andExpect(jsonPath("$[*].sku", contains("P-3500", "P-5000")));
    }

    @Test
    @WithMockUser
    void productsAboveMaxAreExcluded() throws Exception {
        range("20.00", "50.00").andExpect(jsonPath("$[?(@.sku == 'P-5001')]").isEmpty());
        range("20.00", "49.99").andExpect(jsonPath("$[*].sku", contains("P-2000", "P-3500")));
    }

    @Test
    @WithMockUser
    void minEqualToMaxReturnsProductsWithExactlyThatPrice() throws Exception {
        range("50.00", "50.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("P-5000")));
        range("19.99", "19.99").andExpect(jsonPath("$[*].sku", contains("P-1999")));
        range("40.00", "40.00").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void severalResultsAreReturnedInIdOrder() throws Exception {
        range("0.01", "9999999999.99")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("P-1999", "P-2000", "P-3500", "P-5000", "P-5001")));
    }

    @Test
    @WithMockUser
    void severalProductsWithTheSamePriceAreAllReturned() throws Exception {
        save("DUP-A", "35.00");
        save("DUP-B", "35.00");

        range("35.00", "35.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("P-3500", "DUP-A", "DUP-B")));
    }

    @Test
    @WithMockUser
    void noMatchingProductReturnsOkWithEmptyList() throws Exception {
        range("21.00", "34.99").andExpect(status().isOk()).andExpect(content().string("[]"));
        range("0.01", "19.98").andExpect(status().isOk()).andExpect(content().string("[]"));
        range("50.02", "100").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void emptyCatalogReturnsOkWithEmptyList() throws Exception {
        productRepository.deleteAll();

        range("20.00", "50.00").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- BigDecimal / decimals ------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"20, 50", "20.0, 50.0", "20.00, 50.00", "20.000, 50.000", "2e1, 5e1", "020.00, 050.00",
            "+20.00, +50.00"})
    @WithMockUser
    void equivalentRepresentationsOfTheSameRangeGiveTheSameResult(String min, String max) throws Exception {
        range(min, max)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("P-2000", "P-3500", "P-5000")));
    }

    @Test
    @WithMockUser
    void limitsWithMoreDecimalsThanTheColumnAreComparedExactly() throws Exception {
        range("19.995", "20.005").andExpect(jsonPath("$[*].sku", contains("P-2000")));
        range("19.9899", "19.9901").andExpect(jsonPath("$[*].sku", contains("P-1999")));
        range("19.9901", "19.9999").andExpect(status().isOk()).andExpect(content().string("[]"));
        range("50.005", "50.009").andExpect(status().isOk()).andExpect(content().string("[]"));
        range("50.005", "50.010").andExpect(jsonPath("$[*].sku", contains("P-5001")));
    }

    @Test
    @WithMockUser
    void equalLimitsWithDifferentScalesAreAValidRange() throws Exception {
        range("50", "50.00").andExpect(status().isOk()).andExpect(jsonPath("$[*].sku", contains("P-5000")));
        range("50.0", "5e1").andExpect(status().isOk()).andExpect(jsonPath("$[*].sku", contains("P-5000")));
    }

    @Test
    @WithMockUser
    void tinyAndVeryLargeLimitsBehave() throws Exception {
        range("0.0000001", "0.0000002").andExpect(status().isOk()).andExpect(content().string("[]"));
        range("0.01", "99999999999999999999999.99").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(5)));
    }

    // --- 400: min / max missing, non numeric, zero, negative ------------------------------------

    @Test
    @WithMockUser
    void missingMinIsRejected() throws Exception {
        mockMvc.perform(get(URL).param("max", "50.00")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void missingMaxIsRejected() throws Exception {
        mockMvc.perform(get(URL).param("min", "20.00")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void bothMissingAreRejected() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("price", "20.00")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void parametersWithoutValueAreRejected() throws Exception {
        mockMvc.perform(get(URL + "?min&max=50")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?min=20&max")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?min=&max=")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "20,00", "NaN", "Infinity", "$20", "20abc", "abc20", "0x14", "1_000", "20.00.00",
            "1e"})
    @WithMockUser
    void nonNumericMinIsRejected(String min) throws Exception {
        range(min, "50.00").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "50,00", "NaN", "Infinity", "$50", "50abc", "abc50", "0x32", "1_000", "50.00.00",
            "1e"})
    @WithMockUser
    void nonNumericMaxIsRejected(String max) throws Exception {
        range("20.00", max).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "0.00", "-0", "-0.00", "0e0"})
    @WithMockUser
    void zeroMinIsRejected(String min) throws Exception {
        range(min, "50.00").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "0.00", "-0", "-0.00", "0e0"})
    @WithMockUser
    void zeroMaxIsRejected(String max) throws Exception {
        range("20.00", max).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-0.01", "-20.00", "-9999999999.99", "-1e3"})
    @WithMockUser
    void negativeMinIsRejected(String min) throws Exception {
        range(min, "50.00").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-0.01", "-50.00", "-9999999999.99", "-1e3"})
    @WithMockUser
    void negativeMaxIsRejected(String max) throws Exception {
        range("20.00", max).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void bothInvalidAreRejected() throws Exception {
        range("-1", "0").andExpect(status().isBadRequest());
        range("abc", "xyz").andExpect(status().isBadRequest());
    }

    // --- 400: min > max -------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"50.01, 50.00", "50.00, 20.00", "100, 99.99", "0.02, 0.01", "50.001, 50.000", "5e1, 49.99",
            "9999999999.99, 0.01"})
    @WithMockUser
    void minGreaterThanMaxIsRejectedWithAnExplanation(String min, String max) throws Exception {
        range(min, max)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", containsString("min")))
                .andExpect(jsonPath("$.detail", containsString("max")));
    }

    // --- read-only / routing --------------------------------------------------------------------

    @Test
    @WithMockUser
    void queryDoesNotModifyProductsOrTheirData() throws Exception {
        List<String> before = snapshot();

        range("20.00", "50.00").andExpect(status().isOk());
        range("50.00", "20.00").andExpect(status().isBadRequest());
        range("0", "50.00").andExpect(status().isBadRequest());
        range("35.00", "35.00").andExpect(status().isOk());

        assertThat(snapshot()).isEqualTo(before);
        assertThat(productRepository.count()).isEqualTo(5);
    }

    private List<String> snapshot() {
        return productRepository.findAll().stream()
                .map(p -> p.getId() + "|" + p.getName() + "|" + p.getSku() + "|" + p.getPrice().toPlainString()
                        + "|" + p.getStock() + "|" + p.getMinimumStock())
                .sorted()
                .toList();
    }

    @Test
    @WithMockUser
    void routeIsNotTreatedAsASkuAndOtherGetsStillWork() throws Exception {
        save("BY-PRICE-RANGE", "5.00");

        range("5.00", "5.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("BY-PRICE-RANGE"));
        mockMvc.perform(get("/api/products/sku/BY-PRICE-RANGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("BY-PRICE-RANGE"));
        mockMvc.perform(get("/api/products/by-max-price").param("price", "20.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
        mockMvc.perform(get("/api/products/out-of-stock")).andExpect(status().isOk());
        mockMvc.perform(get("/api/products/low-stock")).andExpect(status().isOk());
        mockMvc.perform(get("/api/products/search").param("name", "p-3500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @WithMockUser
    void onlyGetIsSupportedOnTheEndpointAndOtherMethodsDoNotChangeData() throws Exception {
        List<String> before = snapshot();

        mockMvc.perform(post(URL).param("min", "20").param("max", "50")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL).param("min", "20").param("max", "50")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(URL).param("min", "20").param("max", "50"))
                .andExpect(status().isMethodNotAllowed());

        assertThat(snapshot()).isEqualTo(before);
    }

    // --- security -------------------------------------------------------------------------------

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        range("20.00", "50.00")
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void unauthenticatedRequestWithInvalidParametersIsStillUnauthorizedNotBadRequest() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        range("50.00", "20.00").andExpect(status().isUnauthorized());
        range("abc", "-1").andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        mockMvc.perform(get(URL).param("min", "20.00").param("max", "50.00")
                        .with(httpBasic("nexora_test", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).param("min", "20.00").param("max", "50.00")
                        .with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicWithCorrectCredentialsCanQuery() throws Exception {
        mockMvc.perform(get(URL).param("min", "20.00").param("max", "50.00")
                        .with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }
}
