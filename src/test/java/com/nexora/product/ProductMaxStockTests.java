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
class ProductMaxStockTests {

    private static final String URL = "/api/products/by-max-stock";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void seedProducts() {
        productRepository.deleteAll();
        save("Camiseta", "CAM-001", 3, 2);
        save("Sudadera", "SUD-001", 9, 2);
        save("Pantalón", "PAN-001", 10, 2);
        save("Zapatillas", "ZAP-001", 25, 2);
        save("Chaqueta", "CHA-001", 50, 2);
    }

    private void save(String name, String sku, int stock, int minimumStock) {
        productRepository.save(new Product(name, sku, new BigDecimal("19.99"), stock, minimumStock));
    }

    private ResultActions byMaxStock(String stock) throws Exception {
        return mockMvc.perform(get(URL).param("stock", stock));
    }

    // --- matching -------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void productsWithStockAtOrBelowTheThresholdAreReturned() throws Exception {
        byMaxStock("10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].name", contains("Camiseta", "Sudadera", "Pantalón")))
                .andExpect(jsonPath("$[*].stock", contains(3, 9, 10)));
    }

    @Test
    @WithMockUser
    void returnedProductsIncludeAllTheirFields() throws Exception {
        byMaxStock("3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("CAM-001"))
                .andExpect(jsonPath("$[0].name").value("Camiseta"))
                .andExpect(jsonPath("$[0].price").value(19.99))
                .andExpect(jsonPath("$[0].stock").value(3))
                .andExpect(jsonPath("$[0].minimumStock").value(2))
                .andExpect(jsonPath("$[0].id").isNumber());
    }

    @Test
    @WithMockUser
    void productWithStockExactlyEqualToTheThresholdIsIncluded() throws Exception {
        byMaxStock("10").andExpect(jsonPath("$[?(@.sku == 'PAN-001')]").isNotEmpty());
        byMaxStock("50").andExpect(jsonPath("$", hasSize(5)));
    }

    @Test
    @WithMockUser
    void productWithStockBelowTheThresholdIsIncluded() throws Exception {
        byMaxStock("26")
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001", "PAN-001", "ZAP-001")));
    }

    @Test
    @WithMockUser
    void productsWithStockAboveTheThresholdAreExcluded() throws Exception {
        byMaxStock("10")
                .andExpect(jsonPath("$[?(@.sku == 'ZAP-001')]").isEmpty())
                .andExpect(jsonPath("$[?(@.sku == 'CHA-001')]").isEmpty());
        byMaxStock("9").andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
    }

    @Test
    @WithMockUser
    void severalResultsAreReturnedInIdOrder() throws Exception {
        byMaxStock("2147483647")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001", "PAN-001", "ZAP-001", "CHA-001")));
    }

    @Test
    @WithMockUser
    void singleResultIsReturned() throws Exception {
        byMaxStock("8")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("CAM-001"));
    }

    @Test
    @WithMockUser
    void noMatchingProductReturnsOkWithEmptyList() throws Exception {
        byMaxStock("2").andExpect(status().isOk()).andExpect(content().string("[]"));
        byMaxStock("1").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void emptyCatalogReturnsOkWithEmptyList() throws Exception {
        productRepository.deleteAll();

        byMaxStock("10").andExpect(status().isOk()).andExpect(content().string("[]"));
        byMaxStock("0").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- stock = 0 ------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void zeroIsAValidThresholdAndWithoutOutOfStockProductsReturnsEmptyList() throws Exception {
        byMaxStock("0")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void zeroThresholdReturnsOnlyProductsWithStockExactlyZero() throws Exception {
        save("Agotado A", "OUT-A", 0, 5);
        save("Agotado B", "OUT-B", 0, 0);

        byMaxStock("0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].sku", contains("OUT-A", "OUT-B")))
                .andExpect(jsonPath("$[*].stock", contains(0, 0)));
        byMaxStock("3")
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "OUT-A", "OUT-B")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "00", "+0", "-0", "0000"})
    @WithMockUser
    void equivalentRepresentationsOfZeroAreValid(String stock) throws Exception {
        save("Agotado", "OUT-A", 0, 1);

        byMaxStock(stock).andExpect(status().isOk()).andExpect(jsonPath("$[*].sku", contains("OUT-A")));
    }

    // --- 400 ------------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void missingStockParameterIsRejected() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("max", "10")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void stockParameterWithoutValueIsRejected() throws Exception {
        mockMvc.perform(get(URL + "?stock")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?stock=")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "10abc", "abc10", "1,0", "NaN", "Infinity", "$10", "1_0", "10-", "--1",
            "true", "ten"})
    @WithMockUser
    void nonNumericStockIsRejected(String stock) throws Exception {
        byMaxStock(stock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.5", "5.5", "0.5", "10.0", "10.00", "0.0", "1e1", "1E1", "2.5E0", ".5", "10.", "-0.5",
            "-10.5"})
    @WithMockUser
    void decimalStockIsRejected(String stock) throws Exception {
        byMaxStock(stock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-2", "-5", "-10", "-100", "-9999", "-2147483647", "-2147483648"})
    @WithMockUser
    void negativeStockIsRejected(String stock) throws Exception {
        byMaxStock(stock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "99999999999", "99999999999999999999", "-2147483649",
            "-99999999999999999999"})
    @WithMockUser
    void valuesOutsideTheIntegerRangeAreRejected(String stock) throws Exception {
        byMaxStock(stock).andExpect(status().isBadRequest());
    }

    // --- read-only / routing --------------------------------------------------------------------

    @Test
    @WithMockUser
    void queryDoesNotModifyProductsOrTheirData() throws Exception {
        List<String> before = snapshot();

        byMaxStock("10").andExpect(status().isOk());
        byMaxStock("0").andExpect(status().isOk());
        byMaxStock("-1").andExpect(status().isBadRequest());
        byMaxStock("1.5").andExpect(status().isBadRequest());
        byMaxStock("abc").andExpect(status().isBadRequest());

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
    void routeIsNotTreatedAsASkuAndIsDistinctFromLowStock() throws Exception {
        save("By Max Stock", "BY-MAX-STOCK", 1, 0);

        byMaxStock("3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "BY-MAX-STOCK")));
        mockMvc.perform(get("/api/products/sku/BY-MAX-STOCK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(1));
        // /low-stock compares stock with each product's own minimumStock (no product qualifies here: 3 > 2, 1 > 0, ...),
        // whereas /by-max-stock compares stock with the threshold received.
        mockMvc.perform(get("/api/products/low-stock"))
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void lowStockAndMaxStockDoNotReturnTheSameThing() throws Exception {
        save("Bajo minimo", "LOW-1", 5, 5);
        save("Sobre minimo", "HIGH-1", 6, 5);

        mockMvc.perform(get("/api/products/low-stock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("LOW-1")));
        byMaxStock("5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "LOW-1")));
    }

    @Test
    @WithMockUser
    void minStockEndpointAndOtherExistingEndpointsStillWork() throws Exception {
        save("Sin stock", "OUT-OF-STOCK", 0, 1);

        mockMvc.perform(get("/api/products/by-min-stock").param("stock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("PAN-001", "ZAP-001", "CHA-001")));
        mockMvc.perform(get("/api/products/sku/CHA-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Chaqueta"));
        mockMvc.perform(get("/api/products/out-of-stock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("OUT-OF-STOCK")));
        mockMvc.perform(get("/api/products/by-max-price").param("price", "20.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)));
        mockMvc.perform(get("/api/products/by-price-range").param("min", "19.99").param("max", "19.99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)));
        mockMvc.perform(get("/api/products/search").param("name", "chaq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/products/sku/NO-SUCH-SKU")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void onlyGetIsSupportedOnTheEndpointAndOtherMethodsDoNotChangeData() throws Exception {
        List<String> before = snapshot();

        mockMvc.perform(post(URL).param("stock", "10").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL).param("stock", "10").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(URL).param("stock", "10")).andExpect(status().isMethodNotAllowed());

        assertThat(snapshot()).isEqualTo(before);
    }

    // --- security -------------------------------------------------------------------------------

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        byMaxStock("10")
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void unauthenticatedRequestWithInvalidParameterIsStillUnauthorizedNotBadRequest() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        byMaxStock("-1").andExpect(status().isUnauthorized());
        byMaxStock("abc").andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        mockMvc.perform(get(URL).param("stock", "10").with(httpBasic("nexora_test", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).param("stock", "10").with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicWithCorrectCredentialsCanQuery() throws Exception {
        mockMvc.perform(get(URL).param("stock", "10").with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }
}
