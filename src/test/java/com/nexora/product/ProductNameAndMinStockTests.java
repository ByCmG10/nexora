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
class ProductNameAndMinStockTests {

    private static final String URL = "/api/products/search-by-stock";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void seedProducts() {
        productRepository.deleteAll();
        save("Camiseta básica", "CAM-001", 25);
        save("Camiseta premium", "CAM-002", 7);
        save("Sudadera camiseta style", "SUD-001", 15);
        save("Pantalón cargo", "PAN-001", 30);
    }

    private void save(String name, String sku, int stock) {
        productRepository.save(new Product(name, sku, new BigDecimal("19.99"), stock, 2));
    }

    private ResultActions search(String name, String minStock) throws Exception {
        return mockMvc.perform(get(URL).param("name", name).param("minStock", minStock));
    }

    // --- both conditions (AND) ------------------------------------------------------------------

    @Test
    @WithMockUser
    void productsMatchingNameAndMinimumStockAreReturned() throws Exception {
        search("camiseta", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", contains("Camiseta básica", "Sudadera camiseta style")))
                .andExpect(jsonPath("$[*].stock", contains(25, 15)));
    }

    @Test
    @WithMockUser
    void returnedProductsIncludeAllTheirFields() throws Exception {
        search("premium", "5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("CAM-002"))
                .andExpect(jsonPath("$[0].name").value("Camiseta premium"))
                .andExpect(jsonPath("$[0].price").value(19.99))
                .andExpect(jsonPath("$[0].stock").value(7))
                .andExpect(jsonPath("$[0].minimumStock").value(2))
                .andExpect(jsonPath("$[0].id").isNumber());
    }

    @Test
    @WithMockUser
    void productMatchingTheNameButNotTheStockIsExcluded() throws Exception {
        search("camiseta", "10")
                .andExpect(jsonPath("$[?(@.sku == 'CAM-002')]").isEmpty());
    }

    @Test
    @WithMockUser
    void productMatchingTheStockButNotTheNameIsExcluded() throws Exception {
        search("camiseta", "10")
                .andExpect(jsonPath("$[?(@.sku == 'PAN-001')]").isEmpty());
    }

    @Test
    @WithMockUser
    void productMatchingNeitherConditionIsExcluded() throws Exception {
        save("Gorra", "GOR-001", 1);

        search("camiseta", "10")
                .andExpect(jsonPath("$[?(@.sku == 'GOR-001')]").isEmpty());
    }

    @Test
    @WithMockUser
    void bothConditionsAreCombinedWithAndNotOr() throws Exception {
        // With OR, the result would be the 3 "camiseta" products plus Pantalon (stock 30 >= 10) = 4 products.
        search("camiseta", "10")
                .andExpect(jsonPath("$", hasSize(2)));
        // With OR, a name that matches nothing would still return every product with stock >= 20.
        search("zzz", "20")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
        // With OR, an unreachable stock would still return every product whose name matches.
        search("camiseta", "1000")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void severalProductsMeetingBothConditionsAreReturnedInIdOrder() throws Exception {
        save("Camiseta deportiva", "CAM-003", 40);
        save("Camiseta vintage", "CAM-004", 12);

        search("camiseta", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001", "CAM-003", "CAM-004")));
    }

    @Test
    @WithMockUser
    void singleResultIsReturned() throws Exception {
        search("sudadera", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("SUD-001"));
    }

    @Test
    @WithMockUser
    void noProductMeetingBothConditionsReturnsOkWithEmptyList() throws Exception {
        search("camiseta", "26").andExpect(status().isOk()).andExpect(content().string("[]"));
        search("sombrero", "0").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void emptyCatalogReturnsOkWithEmptyList() throws Exception {
        productRepository.deleteAll();

        search("camiseta", "0").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- inclusive limit ------------------------------------------------------------------------

    @Test
    @WithMockUser
    void stockExactlyEqualToMinStockIsIncluded() throws Exception {
        search("camiseta", "15")
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
        search("camiseta", "25")
                .andExpect(jsonPath("$[*].sku", contains("CAM-001")));
        search("premium", "7")
                .andExpect(jsonPath("$[*].sku", contains("CAM-002")));
    }

    @Test
    @WithMockUser
    void stockJustBelowMinStockIsExcluded() throws Exception {
        search("camiseta", "8").andExpect(jsonPath("$[?(@.sku == 'CAM-002')]").isEmpty());
        search("premium", "8").andExpect(content().string("[]"));
    }

    // --- name: partial / case-insensitive / trimmed ---------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"cam", "iseta", "eta b", "sudadera cam", "style", "básica"})
    @WithMockUser
    void partialNameMatchesAnywhereInTheName(String name) throws Exception {
        search(name, "0").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(org.hamcrest.Matchers.greaterThan(0))));
    }

    @Test
    @WithMockUser
    void partialMatchAtDifferentPositionsReturnsTheRightProducts() throws Exception {
        search("style", "0").andExpect(jsonPath("$[*].sku", contains("SUD-001")));
        search("cargo", "0").andExpect(jsonPath("$[*].sku", contains("PAN-001")));
        search("iseta pre", "0").andExpect(jsonPath("$[*].sku", contains("CAM-002")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"camiseta", "CAMISETA", "CaMiSeTa", "Camiseta"})
    @WithMockUser
    void nameSearchIsCaseInsensitive(String name) throws Exception {
        search(name, "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
    }

    @Test
    @WithMockUser
    void caseInsensitivityAlsoWorksWithAccentedCharacters() throws Exception {
        search("PANTALÓN", "0").andExpect(jsonPath("$[*].sku", contains("PAN-001")));
        search("BÁSICA", "0").andExpect(jsonPath("$[*].sku", contains("CAM-001")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"  camiseta", "camiseta  ", "   camiseta   ", "\tcamiseta\t"})
    @WithMockUser
    void leadingAndTrailingWhitespaceInTheNameIsRemoved(String name) throws Exception {
        search(name, "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
    }

    @Test
    @WithMockUser
    void internalWhitespaceOfTheNameIsKept() throws Exception {
        search("  camiseta premium  ", "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "%%", "c_miseta", "cam%eta", "camiseta%"})
    @WithMockUser
    void likeWildcardsInTheNameAreTakenLiterally(String name) throws Exception {
        search(name, "0").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- minStock = 0 ---------------------------------------------------------------------------

    @Test
    @WithMockUser
    void zeroMinStockIsValidAndDoesNotFilterByStock() throws Exception {
        save("Camiseta agotada", "CAM-OUT", 0);

        search("camiseta", "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "SUD-001", "CAM-OUT")));
        search("camiseta", "1")
                .andExpect(jsonPath("$[?(@.sku == 'CAM-OUT')]").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "00", "+0", "-0", "0000"})
    @WithMockUser
    void equivalentRepresentationsOfZeroMinStockAreValid(String minStock) throws Exception {
        search("camiseta", minStock).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)));
    }

    // --- 400: name ------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void missingNameIsRejected() throws Exception {
        mockMvc.perform(get(URL).param("minStock", "10")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void emptyNameIsRejected() throws Exception {
        search("", "10").andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?name&minStock=10")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "   ", "\t", " \t \n "})
    @WithMockUser
    void whitespaceOnlyNameIsRejected(String name) throws Exception {
        search(name, "10").andExpect(status().isBadRequest());
    }

    // --- 400: minStock --------------------------------------------------------------------------

    @Test
    @WithMockUser
    void missingMinStockIsRejected() throws Exception {
        mockMvc.perform(get(URL).param("name", "camiseta")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void bothParametersMissingAreRejected() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("stock", "10")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void minStockWithoutValueIsRejected() throws Exception {
        mockMvc.perform(get(URL + "?name=camiseta&minStock")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?name=camiseta&minStock=")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-2", "-5", "-10", "-100", "-2147483648"})
    @WithMockUser
    void negativeMinStockIsRejected(String minStock) throws Exception {
        search("camiseta", minStock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "10abc", "abc10", "1,0", "NaN", "Infinity", "$10", "1_0", "true", "ten", " "})
    @WithMockUser
    void nonNumericMinStockIsRejected(String minStock) throws Exception {
        search("camiseta", minStock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.5", "0.5", "10.0", "10.00", "0.0", "1e1", "2.5E0", ".5", "10.", "-0.5"})
    @WithMockUser
    void decimalMinStockIsRejected(String minStock) throws Exception {
        search("camiseta", minStock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "99999999999", "99999999999999999999", "-2147483649",
            "-99999999999999999999"})
    @WithMockUser
    void minStockOutsideTheIntegerRangeIsRejected(String minStock) throws Exception {
        search("camiseta", minStock).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void bothParametersInvalidAreRejected() throws Exception {
        search(" ", "-1").andExpect(status().isBadRequest());
        search("", "abc").andExpect(status().isBadRequest());
    }

    // --- read-only / routing --------------------------------------------------------------------

    @Test
    @WithMockUser
    void queryDoesNotModifyProductsOrTheirData() throws Exception {
        List<String> before = snapshot();

        search("camiseta", "10").andExpect(status().isOk());
        search("camiseta", "0").andExpect(status().isOk());
        search("camiseta", "-1").andExpect(status().isBadRequest());
        search(" ", "10").andExpect(status().isBadRequest());
        search("camiseta", "1.5").andExpect(status().isBadRequest());

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
    void routeIsNotTreatedAsASkuAndDoesNotClashWithPlainSearch() throws Exception {
        save("Search By Stock", "SEARCH-BY-STOCK", 5);

        search("search by", "5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SEARCH-BY-STOCK")));
        mockMvc.perform(get("/api/products/sku/SEARCH-BY-STOCK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(5));
        // The plain name search ignores stock entirely and still works with a single parameter.
        mockMvc.perform(get("/api/products/search").param("name", "camiseta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "SUD-001")));
    }

    @Test
    @WithMockUser
    void otherExistingEndpointsStillWork() throws Exception {
        mockMvc.perform(get("/api/products/by-min-stock").param("stock", "15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001", "PAN-001")));
        mockMvc.perform(get("/api/products/by-max-stock").param("stock", "15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "SUD-001")));
        mockMvc.perform(get("/api/products/by-stock").param("stock", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002")));
        mockMvc.perform(get("/api/products/out-of-stock"))
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
        mockMvc.perform(get("/api/products/low-stock")).andExpect(status().isOk());
        mockMvc.perform(get("/api/products/by-max-price").param("price", "20.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));
        mockMvc.perform(get("/api/products/by-price-range").param("min", "19.99").param("max", "19.99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));
        mockMvc.perform(get("/api/products/sku/PAN-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pantalón cargo"));
        mockMvc.perform(get("/api/products/sku/NO-SUCH-SKU")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void onlyGetIsSupportedOnTheEndpointAndOtherMethodsDoNotChangeData() throws Exception {
        List<String> before = snapshot();

        mockMvc.perform(post(URL).param("name", "camiseta").param("minStock", "10")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL).param("name", "camiseta").param("minStock", "10")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(URL).param("name", "camiseta").param("minStock", "10"))
                .andExpect(status().isMethodNotAllowed());

        assertThat(snapshot()).isEqualTo(before);
    }

    // --- security -------------------------------------------------------------------------------

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        search("camiseta", "10")
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void unauthenticatedRequestWithInvalidParametersIsStillUnauthorizedNotBadRequest() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        search(" ", "-1").andExpect(status().isUnauthorized());
        search("camiseta", "abc").andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        mockMvc.perform(get(URL).param("name", "camiseta").param("minStock", "10")
                        .with(httpBasic("nexora_test", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).param("name", "camiseta").param("minStock", "10")
                        .with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicWithCorrectCredentialsCanQuery() throws Exception {
        mockMvc.perform(get(URL).param("name", "camiseta").param("minStock", "10")
                        .with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }
}
