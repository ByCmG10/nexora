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
import static org.hamcrest.Matchers.greaterThan;
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
class ProductNameAndMaxStockTests {

    private static final String URL = "/api/products/search-by-max-stock";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void seedProducts() {
        productRepository.deleteAll();
        save("Camiseta básica", "CAM-001", 25);
        save("Camiseta premium", "CAM-002", 7);
        save("Sudadera camiseta style", "SUD-001", 10);
        save("Pantalón cargo", "PAN-001", 3);
    }

    private void save(String name, String sku, int stock) {
        productRepository.save(new Product(name, sku, new BigDecimal("19.99"), stock, 2));
    }

    private ResultActions search(String name, String maxStock) throws Exception {
        return mockMvc.perform(get(URL).param("name", name).param("maxStock", maxStock));
    }

    // --- both conditions (AND) ------------------------------------------------------------------

    @Test
    @WithMockUser
    void productsMatchingNameAndMaxStockAreReturned() throws Exception {
        search("camiseta", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", contains("Camiseta premium", "Sudadera camiseta style")))
                .andExpect(jsonPath("$[*].stock", contains(7, 10)));
    }

    @Test
    @WithMockUser
    void returnedProductsIncludeAllTheirFields() throws Exception {
        search("premium", "7")
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
    void productMatchingTheNameButWithStockAboveMaxStockIsExcluded() throws Exception {
        search("camiseta", "10").andExpect(jsonPath("$[?(@.sku == 'CAM-001')]").isEmpty());
        search("camiseta", "24").andExpect(jsonPath("$[?(@.sku == 'CAM-001')]").isEmpty());
    }

    @Test
    @WithMockUser
    void productMatchingTheStockButNotTheNameIsExcluded() throws Exception {
        search("camiseta", "10").andExpect(jsonPath("$[?(@.sku == 'PAN-001')]").isEmpty());
        search("camiseta", "3").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void productMatchingNeitherConditionIsExcluded() throws Exception {
        save("Gorra", "GOR-001", 500);

        search("camiseta", "10").andExpect(jsonPath("$[?(@.sku == 'GOR-001')]").isEmpty());
    }

    @Test
    @WithMockUser
    void bothConditionsAreCombinedWithAndNotOr() throws Exception {
        // With OR, Pantalon (stock 3 <= 10) would also appear: 3 products instead of 2.
        search("camiseta", "10").andExpect(jsonPath("$", hasSize(2)));
        // With OR, a name that matches nothing would still return every product with stock <= 20.
        search("zzz", "20").andExpect(status().isOk()).andExpect(content().string("[]"));
        // With OR, an unreachable low stock would still return every product whose name matches.
        search("camiseta", "0").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- inclusive limit ------------------------------------------------------------------------

    @Test
    @WithMockUser
    void stockExactlyEqualToMaxStockIsIncluded() throws Exception {
        search("camiseta", "10").andExpect(jsonPath("$[?(@.sku == 'SUD-001')]").isNotEmpty());
        search("camiseta", "7").andExpect(jsonPath("$[*].sku", contains("CAM-002")));
        search("camiseta", "25").andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    @WithMockUser
    void stockJustAboveMaxStockIsExcluded() throws Exception {
        search("camiseta", "6").andExpect(status().isOk()).andExpect(content().string("[]"));
        search("camiseta", "9").andExpect(jsonPath("$[*].sku", contains("CAM-002")));
    }

    // --- ordering / number of results -----------------------------------------------------------

    @Test
    @WithMockUser
    void resultsAreOrderedByIdAscendingRegardlessOfStockOrName() throws Exception {
        save("Camiseta cero", "CAM-003", 0);
        save("Camiseta media", "CAM-004", 5);
        save("Camiseta ultima", "CAM-005", 8);

        search("camiseta", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "SUD-001", "CAM-003", "CAM-004", "CAM-005")));
    }

    @Test
    @WithMockUser
    void idsInTheResponseAreAscending() throws Exception {
        save("Camiseta cero", "CAM-003", 0);
        int first = productRepository.findBySku("CAM-002").orElseThrow().getId().intValue();
        int second = productRepository.findBySku("SUD-001").orElseThrow().getId().intValue();
        int third = productRepository.findBySku("CAM-003").orElseThrow().getId().intValue();
        assertThat(first).isLessThan(second);
        assertThat(second).isLessThan(third);

        // CAM-003 has the lowest stock but was inserted last, so ordering by id puts it last.
        search("camiseta", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[1].id").value(second))
                .andExpect(jsonPath("$[2].id").value(third));
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
    void noMatchesReturnsOkWithEmptyList() throws Exception {
        search("sombrero", "1000").andExpect(status().isOk()).andExpect(content().string("[]"));
        search("camiseta", "6").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void emptyCatalogReturnsOkWithEmptyList() throws Exception {
        productRepository.deleteAll();

        search("camiseta", "10").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- name: partial / case-insensitive / trimmed ---------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"cam", "iseta", "eta p", "sudadera cam", "style", "premium"})
    @WithMockUser
    void partialNameMatchesAnywhereInTheName(String name) throws Exception {
        search(name, "1000").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(greaterThan(0))));
    }

    @Test
    @WithMockUser
    void partialMatchAtDifferentPositionsReturnsTheRightProducts() throws Exception {
        search("style", "100").andExpect(jsonPath("$[*].sku", contains("SUD-001")));
        search("cargo", "100").andExpect(jsonPath("$[*].sku", contains("PAN-001")));
        search("iseta pre", "100").andExpect(jsonPath("$[*].sku", contains("CAM-002")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"camiseta", "CAMISETA", "CaMiSeTa", "Camiseta"})
    @WithMockUser
    void nameSearchIsCaseInsensitive(String name) throws Exception {
        search(name, "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "SUD-001")));
    }

    @Test
    @WithMockUser
    void caseInsensitivityAlsoWorksWithAccentedCharacters() throws Exception {
        search("PANTALÓN", "100").andExpect(jsonPath("$[*].sku", contains("PAN-001")));
        search("BÁSICA", "100").andExpect(jsonPath("$[*].sku", contains("CAM-001")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"  camiseta", "camiseta  ", "   camiseta   ", "\tcamiseta\t"})
    @WithMockUser
    void leadingAndTrailingWhitespaceInTheNameIsRemoved(String name) throws Exception {
        search(name, "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "SUD-001")));
    }

    @Test
    @WithMockUser
    void internalWhitespaceOfTheNameIsKept() throws Exception {
        search("  camiseta premium  ", "100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "%%", "c_miseta", "cam%eta", "camiseta%"})
    @WithMockUser
    void likeWildcardsInTheNameAreTakenLiterally(String name) throws Exception {
        search(name, "1000").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- maxStock = 0 ---------------------------------------------------------------------------

    @Test
    @WithMockUser
    void zeroMaxStockIsValidAndReturnsOnlyProductsWithZeroStock() throws Exception {
        save("Camiseta agotada", "CAM-OUT", 0);

        search("camiseta", "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-OUT")))
                .andExpect(jsonPath("$[*].stock", contains(0)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "00", "+0", "-0", "0000"})
    @WithMockUser
    void equivalentRepresentationsOfZeroMaxStockAreValid(String maxStock) throws Exception {
        save("Camiseta agotada", "CAM-OUT", 0);

        search("camiseta", maxStock).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    // --- 400: name ------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void missingNameIsRejected() throws Exception {
        mockMvc.perform(get(URL).param("maxStock", "10")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void emptyNameIsRejected() throws Exception {
        search("", "10").andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?name&maxStock=10")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "   ", "\t", " \t \n "})
    @WithMockUser
    void whitespaceOnlyNameIsRejected(String name) throws Exception {
        search(name, "10").andExpect(status().isBadRequest());
    }

    // --- 400: maxStock --------------------------------------------------------------------------

    @Test
    @WithMockUser
    void missingMaxStockIsRejected() throws Exception {
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
    void maxStockWithoutValueIsRejected() throws Exception {
        mockMvc.perform(get(URL + "?name=camiseta&maxStock")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?name=camiseta&maxStock=")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-2", "-5", "-10", "-100", "-2147483648"})
    @WithMockUser
    void negativeMaxStockIsRejected(String maxStock) throws Exception {
        search("camiseta", maxStock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "10abc", "abc10", "1,0", "NaN", "Infinity", "$10", "1_0", "true", "ten", " "})
    @WithMockUser
    void nonNumericMaxStockIsRejected(String maxStock) throws Exception {
        search("camiseta", maxStock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.5", "0.5", "10.0", "10.00", "0.0", "1e1", "2.5E0", ".5", "10.", "-0.5"})
    @WithMockUser
    void decimalMaxStockIsRejected(String maxStock) throws Exception {
        search("camiseta", maxStock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "99999999999", "99999999999999999999", "-2147483649",
            "-99999999999999999999"})
    @WithMockUser
    void maxStockOutsideTheIntegerRangeIsRejected(String maxStock) throws Exception {
        search("camiseta", maxStock).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void maximumIntegerMaxStockIsValidAndReturnsEveryNameMatch() throws Exception {
        search("camiseta", "2147483647").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)));
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
    void routeIsNotTreatedAsASkuAndIsDistinctFromTheMinStockSearch() throws Exception {
        save("Search By Max Stock", "SEARCH-BY-MAX", 2);

        search("search by", "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SEARCH-BY-MAX")));
        mockMvc.perform(get("/api/products/sku/SEARCH-BY-MAX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(2));
        // /search-by-stock (NXR-015) is the inverse: stock >= minimum.
        mockMvc.perform(get("/api/products/search-by-stock").param("name", "camiseta").param("minStock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
        search("camiseta", "10")
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "SUD-001")));
    }

    @Test
    @WithMockUser
    void otherExistingEndpointsStillWork() throws Exception {
        mockMvc.perform(get("/api/products/search").param("name", "camiseta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
        mockMvc.perform(get("/api/products/search-by-price").param("name", "camiseta").param("maxPrice", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
        mockMvc.perform(get("/api/products/by-min-stock").param("stock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
        mockMvc.perform(get("/api/products/by-max-stock").param("stock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "SUD-001", "PAN-001")));
        mockMvc.perform(get("/api/products/by-stock").param("stock", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002")));
        mockMvc.perform(get("/api/products/by-max-price").param("price", "20.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));
        mockMvc.perform(get("/api/products/by-price-range").param("min", "19.99").param("max", "19.99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));
        mockMvc.perform(get("/api/products/out-of-stock"))
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
        mockMvc.perform(get("/api/products/low-stock")).andExpect(status().isOk());
        mockMvc.perform(get("/api/products/sku/PAN-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pantalón cargo"));
        mockMvc.perform(get("/api/products/sku/NO-SUCH-SKU")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void onlyGetIsSupportedOnTheEndpointAndOtherMethodsDoNotChangeData() throws Exception {
        List<String> before = snapshot();

        mockMvc.perform(post(URL).param("name", "camiseta").param("maxStock", "10")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL).param("name", "camiseta").param("maxStock", "10")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(URL).param("name", "camiseta").param("maxStock", "10"))
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
        mockMvc.perform(get(URL).param("name", "camiseta").param("maxStock", "10")
                        .with(httpBasic("nexora_test", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).param("name", "camiseta").param("maxStock", "10")
                        .with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicWithCorrectCredentialsCanQuery() throws Exception {
        mockMvc.perform(get(URL).param("name", "camiseta").param("maxStock", "10")
                        .with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }
}
