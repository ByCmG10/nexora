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
class ProductExactStockTests {

    private static final String URL = "/api/products/by-stock";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void seedProducts() {
        productRepository.deleteAll();
        save("Camiseta", "CAM-001", 3);
        save("Sudadera", "SUD-001", 10);
        save("Pantalón", "PAN-001", 10);
        save("Zapatillas", "ZAP-001", 25);
        save("Chaqueta", "CHA-001", 50);
    }

    private void save(String name, String sku, int stock) {
        productRepository.save(new Product(name, sku, new BigDecimal("19.99"), stock, 2));
    }

    private ResultActions byStock(String stock) throws Exception {
        return mockMvc.perform(get(URL).param("stock", stock));
    }

    // --- exact matching -------------------------------------------------------------------------

    @Test
    @WithMockUser
    void onlyProductsWithExactlyThatStockAreReturned() throws Exception {
        byStock("10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", contains("Sudadera", "Pantalón")))
                .andExpect(jsonPath("$[*].stock", contains(10, 10)));
    }

    @Test
    @WithMockUser
    void returnedProductsIncludeAllTheirFields() throws Exception {
        byStock("25")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("ZAP-001"))
                .andExpect(jsonPath("$[0].name").value("Zapatillas"))
                .andExpect(jsonPath("$[0].price").value(19.99))
                .andExpect(jsonPath("$[0].stock").value(25))
                .andExpect(jsonPath("$[0].minimumStock").value(2))
                .andExpect(jsonPath("$[0].id").isNumber());
    }

    @Test
    @WithMockUser
    void productsWithLowerStockAreNotIncluded() throws Exception {
        byStock("10")
                .andExpect(jsonPath("$[?(@.sku == 'CAM-001')]").isEmpty());
        byStock("25")
                .andExpect(jsonPath("$[?(@.sku == 'CAM-001')]").isEmpty())
                .andExpect(jsonPath("$[?(@.sku == 'SUD-001')]").isEmpty())
                .andExpect(jsonPath("$[?(@.sku == 'PAN-001')]").isEmpty());
    }

    @Test
    @WithMockUser
    void productsWithHigherStockAreNotIncluded() throws Exception {
        byStock("10")
                .andExpect(jsonPath("$[?(@.sku == 'ZAP-001')]").isEmpty())
                .andExpect(jsonPath("$[?(@.sku == 'CHA-001')]").isEmpty());
        byStock("3").andExpect(jsonPath("$[*].sku", contains("CAM-001")));
    }

    @Test
    @WithMockUser
    void severalProductsWithExactlyTheSameStockAreAllReturnedInIdOrder() throws Exception {
        save("Otra A", "DUP-A", 10);
        save("Otra B", "DUP-B", 10);

        byStock("10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "PAN-001", "DUP-A", "DUP-B")));
    }

    @Test
    @WithMockUser
    void singleResultIsReturned() throws Exception {
        byStock("50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("CHA-001"));
    }

    @Test
    @WithMockUser
    void valuesJustBesideAnExistingStockDoNotMatch() throws Exception {
        byStock("9").andExpect(status().isOk()).andExpect(content().string("[]"));
        byStock("11").andExpect(status().isOk()).andExpect(content().string("[]"));
        byStock("2").andExpect(status().isOk()).andExpect(content().string("[]"));
        byStock("4").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void noMatchingProductReturnsOkWithEmptyList() throws Exception {
        byStock("7").andExpect(status().isOk()).andExpect(content().string("[]"));
        byStock("2147483647").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void emptyCatalogReturnsOkWithEmptyList() throws Exception {
        productRepository.deleteAll();

        byStock("10").andExpect(status().isOk()).andExpect(content().string("[]"));
        byStock("0").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void maximumIntegerStockCanBeMatchedExactly() throws Exception {
        save("Infinito", "INF-001", 2147483647);

        byStock("2147483647")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("INF-001")));
    }

    // --- stock = 0 ------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void zeroIsValidAndWithoutProductsOfZeroStockReturnsEmptyList() throws Exception {
        byStock("0")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void zeroReturnsExclusivelyProductsWithStockExactlyZero() throws Exception {
        save("Agotado A", "OUT-A", 0);
        save("Agotado B", "OUT-B", 0);

        byStock("0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].sku", contains("OUT-A", "OUT-B")))
                .andExpect(jsonPath("$[*].stock", contains(0, 0)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "00", "+0", "-0", "0000"})
    @WithMockUser
    void equivalentRepresentationsOfZeroAreValid(String stock) throws Exception {
        save("Agotado", "OUT-A", 0);

        byStock(stock).andExpect(status().isOk()).andExpect(jsonPath("$[*].sku", contains("OUT-A")));
    }

    // --- 400 ------------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void missingStockParameterIsRejected() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("quantity", "10")).andExpect(status().isBadRequest());
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
        byStock(stock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.5", "5.5", "0.5", "10.0", "10.00", "0.0", "1e1", "1E1", "2.5E0", ".5", "10.", "-0.5",
            "-10.5"})
    @WithMockUser
    void decimalStockIsRejected(String stock) throws Exception {
        byStock(stock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-2", "-5", "-10", "-100", "-9999", "-2147483647", "-2147483648"})
    @WithMockUser
    void negativeStockIsRejected(String stock) throws Exception {
        byStock(stock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "99999999999", "99999999999999999999", "-2147483649",
            "-99999999999999999999"})
    @WithMockUser
    void valuesOutsideTheIntegerRangeAreRejected(String stock) throws Exception {
        byStock(stock).andExpect(status().isBadRequest());
    }

    // --- read-only / routing --------------------------------------------------------------------

    @Test
    @WithMockUser
    void queryDoesNotModifyProductsOrTheirData() throws Exception {
        List<String> before = snapshot();

        byStock("10").andExpect(status().isOk());
        byStock("0").andExpect(status().isOk());
        byStock("-1").andExpect(status().isBadRequest());
        byStock("1.5").andExpect(status().isBadRequest());
        byStock("abc").andExpect(status().isBadRequest());

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
    void routeIsNotTreatedAsASku() throws Exception {
        save("By Stock", "BY-STOCK", 7);

        byStock("7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("BY-STOCK")));
        mockMvc.perform(get("/api/products/sku/BY-STOCK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(7));
    }

    @Test
    @WithMockUser
    void isAGenericExactFilterDistinctFromOutOfStock() throws Exception {
        save("Agotado", "OUT-A", 0);

        // /out-of-stock is fixed to 0; /by-stock accepts any quantity.
        mockMvc.perform(get("/api/products/out-of-stock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("OUT-A")));
        byStock("0").andExpect(jsonPath("$[*].sku", contains("OUT-A")));
        byStock("25").andExpect(jsonPath("$[*].sku", contains("ZAP-001")));
        mockMvc.perform(get("/api/products/out-of-stock"))
                .andExpect(jsonPath("$[?(@.sku == 'ZAP-001')]").isEmpty());
    }

    @Test
    @WithMockUser
    void minAndMaxStockEndpointsStillWorkAndDifferFromExactMatch() throws Exception {
        mockMvc.perform(get("/api/products/by-min-stock").param("stock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "PAN-001", "ZAP-001", "CHA-001")));
        mockMvc.perform(get("/api/products/by-max-stock").param("stock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001", "PAN-001")));
        byStock("10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "PAN-001")));
    }

    @Test
    @WithMockUser
    void otherExistingEndpointsStillWork() throws Exception {
        mockMvc.perform(get("/api/products/sku/CHA-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Chaqueta"));
        mockMvc.perform(get("/api/products/low-stock")).andExpect(status().isOk());
        mockMvc.perform(get("/api/products/by-max-price").param("price", "20.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
        mockMvc.perform(get("/api/products/by-price-range").param("min", "19.99").param("max", "19.99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
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
        byStock("10")
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void unauthenticatedRequestWithInvalidParameterIsStillUnauthorizedNotBadRequest() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        byStock("-1").andExpect(status().isUnauthorized());
        byStock("abc").andExpect(status().isUnauthorized());
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
                .andExpect(jsonPath("$", hasSize(2)));
    }
}
