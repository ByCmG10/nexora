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
class ProductMaxPriceTests {

    private static final String URL = "/api/products/by-max-price";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void seedProducts() {
        productRepository.deleteAll();
        save("P-1999", "19.99");
        save("P-5000", "50.00");
        save("P-5001", "50.01");
        save("P-9000", "90.00");
    }

    private void save(String sku, String price) {
        productRepository.save(new Product("Product " + sku, sku, new BigDecimal(price), 10, 2));
    }

    private ResultActions byMaxPrice(String price) throws Exception {
        return mockMvc.perform(get(URL).param("price", price));
    }

    // --- matching -------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void productsAtOrBelowTheLimitAreReturnedAndHigherOnesExcluded() throws Exception {
        byMaxPrice("50.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].sku", contains("P-1999", "P-5000")))
                .andExpect(jsonPath("$[*].price", contains(19.99, 50.00)));
    }

    @Test
    @WithMockUser
    void returnedProductsIncludeAllTheirFields() throws Exception {
        byMaxPrice("19.99")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("P-1999"))
                .andExpect(jsonPath("$[0].name").value("Product P-1999"))
                .andExpect(jsonPath("$[0].price").value(19.99))
                .andExpect(jsonPath("$[0].stock").value(10))
                .andExpect(jsonPath("$[0].minimumStock").value(2))
                .andExpect(jsonPath("$[0].id").isNumber());
    }

    @Test
    @WithMockUser
    void productWithPriceExactlyEqualToTheLimitIsReturned() throws Exception {
        byMaxPrice("19.99")
                .andExpect(jsonPath("$[*].sku", contains("P-1999")));
        byMaxPrice("90.00")
                .andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    @WithMockUser
    void productJustAboveTheLimitIsExcluded() throws Exception {
        byMaxPrice("50.00").andExpect(jsonPath("$[?(@.sku == 'P-5001')]").isEmpty());
        byMaxPrice("19.98").andExpect(status().isOk()).andExpect(content().string("[]"));
        byMaxPrice("49.99").andExpect(jsonPath("$[*].sku", contains("P-1999")));
    }

    @Test
    @WithMockUser
    void severalProductsAreReturnedInIdOrder() throws Exception {
        byMaxPrice("1000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("P-1999", "P-5000", "P-5001", "P-9000")));
    }

    @Test
    @WithMockUser
    void noProductMeetingTheLimitReturnsOkWithEmptyList() throws Exception {
        byMaxPrice("10.00")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
        byMaxPrice("0.01")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void emptyCatalogReturnsOkWithEmptyList() throws Exception {
        productRepository.deleteAll();

        byMaxPrice("50.00")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    // --- BigDecimal / decimals ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"50", "50.0", "50.00", "50.000", "5e1", "5E+1", "0050.00", "+50.00"})
    @WithMockUser
    void equivalentRepresentationsOfTheSameLimitGiveTheSameResult(String price) throws Exception {
        byMaxPrice(price)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("P-1999", "P-5000")));
    }

    @Test
    @WithMockUser
    void limitsWithMoreDecimalsThanTheColumnAreComparedExactly() throws Exception {
        byMaxPrice("50.009").andExpect(jsonPath("$[*].sku", contains("P-1999", "P-5000")));
        byMaxPrice("50.010").andExpect(jsonPath("$[*].sku", contains("P-1999", "P-5000", "P-5001")));
        byMaxPrice("19.9899").andExpect(content().string("[]"));
        byMaxPrice("19.9900").andExpect(jsonPath("$[*].sku", contains("P-1999")));
    }

    @Test
    @WithMockUser
    void tinyAndVeryLargeLimitsBehave() throws Exception {
        byMaxPrice("0.0000001").andExpect(status().isOk()).andExpect(content().string("[]"));
        byMaxPrice("9999999999.99").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(4)));
        byMaxPrice("99999999999999999999999.99").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(4)));
    }

    // --- 400 ------------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void missingPriceIsRejected() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("other", "50")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "50,00", "NaN", "Infinity", "-Infinity", "50.00.00", "1e", "$50",
            "10,20", "50abc", "abc50", "0x32", "1_000"})
    @WithMockUser
    void emptyOrNonNumericPriceIsRejected(String price) throws Exception {
        byMaxPrice(price).andExpect(status().isBadRequest());
    }

    /** Spring's number conversion removes ALL whitespace before parsing, so "5 0" is read as 50. */
    @ParameterizedTest
    @ValueSource(strings = {" 50.00", "50.00 ", "  50.00  ", "5 0", "5 0 . 0 0", "\t50\t"})
    @WithMockUser
    void whitespaceInThePriceIsIgnoredByTheFrameworkConversion(String price) throws Exception {
        byMaxPrice(price)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("P-1999", "P-5000")));
    }

    @Test
    @WithMockUser
    void priceParameterWithoutValueIsRejected() throws Exception {
        mockMvc.perform(get(URL + "?price")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "0.00", "-0", "-0.00", "0e0"})
    @WithMockUser
    void zeroPriceIsRejected(String price) throws Exception {
        byMaxPrice(price).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-0.01", "-50.00", "-9999999999.99", "-1e3"})
    @WithMockUser
    void negativePriceIsRejected(String price) throws Exception {
        byMaxPrice(price).andExpect(status().isBadRequest());
    }

    // --- read-only / routing --------------------------------------------------------------------

    @Test
    @WithMockUser
    void queryDoesNotModifyProductsOrTheirData() throws Exception {
        List<String> before = snapshot();

        byMaxPrice("50.00").andExpect(status().isOk());
        byMaxPrice("0").andExpect(status().isBadRequest());
        byMaxPrice("abc").andExpect(status().isBadRequest());
        byMaxPrice("1000").andExpect(status().isOk());

        assertThat(snapshot()).isEqualTo(before);
        assertThat(productRepository.count()).isEqualTo(4);
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
        save("BY-MAX-PRICE", "5.00");

        byMaxPrice("5.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("BY-MAX-PRICE"));
        mockMvc.perform(get("/api/products/sku/BY-MAX-PRICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("BY-MAX-PRICE"));
        mockMvc.perform(get("/api/products/out-of-stock")).andExpect(status().isOk());
        mockMvc.perform(get("/api/products/low-stock")).andExpect(status().isOk());
        mockMvc.perform(get("/api/products/search").param("name", "p-9000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @WithMockUser
    void onlyGetIsSupportedOnTheEndpointAndOtherMethodsDoNotChangeData() throws Exception {
        List<String> before = snapshot();

        mockMvc.perform(post(URL).param("price", "50").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL).param("price", "50").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(URL).param("price", "50")).andExpect(status().isMethodNotAllowed());

        assertThat(snapshot()).isEqualTo(before);
    }

    // --- security -------------------------------------------------------------------------------

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        byMaxPrice("50.00")
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void unauthenticatedRequestWithInvalidPriceIsStillUnauthorizedNotBadRequest() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        byMaxPrice("-1").andExpect(status().isUnauthorized());
        byMaxPrice("abc").andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        mockMvc.perform(get(URL).param("price", "50.00").with(httpBasic("nexora_test", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).param("price", "50.00").with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicWithCorrectCredentialsCanQuery() throws Exception {
        mockMvc.perform(get(URL).param("price", "50.00").with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }
}
