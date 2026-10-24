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
class ProductNameAndMaxPriceTests {

    private static final String URL = "/api/products/search-by-price";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void seedProducts() {
        productRepository.deleteAll();
        save("Camiseta básica", "CAM-001", "19.99");
        save("Camiseta premium", "CAM-002", "89.90");
        save("Sudadera camiseta style", "SUD-001", "50.00");
        save("Pantalón cargo", "PAN-001", "35.00");
    }

    private void save(String name, String sku, String price) {
        productRepository.save(new Product(name, sku, new BigDecimal(price), 10, 2));
    }

    private ResultActions search(String name, String maxPrice) throws Exception {
        return mockMvc.perform(get(URL).param("name", name).param("maxPrice", maxPrice));
    }

    // --- both conditions (AND) ------------------------------------------------------------------

    @Test
    @WithMockUser
    void productsMatchingNameAndMaxPriceAreReturned() throws Exception {
        search("camiseta", "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", contains("Camiseta básica", "Sudadera camiseta style")))
                .andExpect(jsonPath("$[*].price", contains(19.99, 50.00)));
    }

    @Test
    @WithMockUser
    void returnedProductsIncludeAllTheirFields() throws Exception {
        search("cargo", "35.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("PAN-001"))
                .andExpect(jsonPath("$[0].name").value("Pantalón cargo"))
                .andExpect(jsonPath("$[0].price").value(35.00))
                .andExpect(jsonPath("$[0].stock").value(10))
                .andExpect(jsonPath("$[0].minimumStock").value(2))
                .andExpect(jsonPath("$[0].id").isNumber());
    }

    @Test
    @WithMockUser
    void productWithPriceExactlyEqualToMaxPriceIsIncluded() throws Exception {
        search("camiseta", "50.00")
                .andExpect(jsonPath("$[?(@.sku == 'SUD-001')]").isNotEmpty());
        search("camiseta", "19.99")
                .andExpect(jsonPath("$[*].sku", contains("CAM-001")));
        search("premium", "89.90")
                .andExpect(jsonPath("$[*].sku", contains("CAM-002")));
    }

    @Test
    @WithMockUser
    void productsWithPriceAboveMaxPriceAreExcluded() throws Exception {
        search("camiseta", "50")
                .andExpect(jsonPath("$[?(@.sku == 'CAM-002')]").isEmpty());
        search("camiseta", "49.99")
                .andExpect(jsonPath("$[*].sku", contains("CAM-001")));
        search("premium", "89.89")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void productsWhoseNameDoesNotMatchAreExcludedEvenIfCheap() throws Exception {
        search("camiseta", "1000")
                .andExpect(jsonPath("$[?(@.sku == 'PAN-001')]").isEmpty());
        search("camiseta", "50")
                .andExpect(jsonPath("$[?(@.sku == 'PAN-001')]").isEmpty());
    }

    @Test
    @WithMockUser
    void bothConditionsAreCombinedWithAndNotOr() throws Exception {
        // With OR, the Pantalon (35.00 <= 50) would also appear: 3 products instead of 2.
        search("camiseta", "50").andExpect(jsonPath("$", hasSize(2)));
        // With OR, a name that matches nothing would still return every product cheaper than 100.
        search("zzz", "100").andExpect(status().isOk()).andExpect(content().string("[]"));
        // With OR, an unreachable price would still return every product whose name matches.
        search("camiseta", "0.01").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- ordering / number of results -----------------------------------------------------------

    @Test
    @WithMockUser
    void resultsAreOrderedByIdAscendingRegardlessOfPriceOrName() throws Exception {
        save("Camiseta barata", "CAM-003", "1.00");
        save("Camiseta media", "CAM-004", "30.00");
        save("Camiseta ultima", "CAM-005", "10.00");

        search("camiseta", "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001", "CAM-003", "CAM-004", "CAM-005")));
    }

    @Test
    @WithMockUser
    void idsInTheResponseAreStrictlyAscending() throws Exception {
        save("Camiseta barata", "CAM-003", "1.00");
        int first = productRepository.findBySku("CAM-001").orElseThrow().getId().intValue();
        int second = productRepository.findBySku("SUD-001").orElseThrow().getId().intValue();
        int third = productRepository.findBySku("CAM-003").orElseThrow().getId().intValue();
        assertThat(first).isLessThan(second);
        assertThat(second).isLessThan(third);

        // The cheapest product (CAM-003) was inserted last, so ordering by id puts it last, not first.
        search("camiseta", "100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sku == 'CAM-002')]").isNotEmpty())
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[1].id").value(org.hamcrest.Matchers.greaterThan(first)))
                .andExpect(jsonPath("$[3].id").value(third));
    }

    @Test
    @WithMockUser
    void singleResultIsReturned() throws Exception {
        search("sudadera", "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("SUD-001"));
    }

    @Test
    @WithMockUser
    void noMatchesReturnsOkWithEmptyList() throws Exception {
        search("camiseta", "10").andExpect(status().isOk()).andExpect(content().string("[]"));
        search("sombrero", "1000").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void emptyCatalogReturnsOkWithEmptyList() throws Exception {
        productRepository.deleteAll();

        search("camiseta", "50").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- name: partial / case-insensitive / trimmed ---------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"cam", "iseta", "eta b", "sudadera cam", "style", "básica"})
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
        search(name, "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
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
        search(name, "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
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

    // --- maxPrice: BigDecimal -------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"50", "50.0", "50.00", "50.000", "5e1", "5E+1", "0050.00", "+50.00"})
    @WithMockUser
    void equivalentRepresentationsOfTheSameMaxPriceGiveTheSameResult(String maxPrice) throws Exception {
        search("camiseta", maxPrice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
    }

    @Test
    @WithMockUser
    void maxPriceWithMoreDecimalsThanTheColumnIsComparedExactly() throws Exception {
        search("camiseta", "49.999").andExpect(jsonPath("$[*].sku", contains("CAM-001")));
        search("camiseta", "50.001").andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
        search("camiseta", "19.9899").andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void tinyAndVeryLargeMaxPricesBehave() throws Exception {
        search("camiseta", "0.0000001").andExpect(status().isOk()).andExpect(content().string("[]"));
        search("camiseta", "99999999999999999999999.99")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }

    // --- 400: name ------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void missingNameIsRejected() throws Exception {
        mockMvc.perform(get(URL).param("maxPrice", "50")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void emptyNameIsRejected() throws Exception {
        search("", "50").andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?name&maxPrice=50")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "   ", "\t", " \t \n "})
    @WithMockUser
    void whitespaceOnlyNameIsRejected(String name) throws Exception {
        search(name, "50").andExpect(status().isBadRequest());
    }

    // --- 400: maxPrice --------------------------------------------------------------------------

    @Test
    @WithMockUser
    void missingMaxPriceIsRejected() throws Exception {
        mockMvc.perform(get(URL).param("name", "camiseta")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void bothParametersMissingAreRejected() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("price", "50")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void maxPriceWithoutValueIsRejected() throws Exception {
        mockMvc.perform(get(URL + "?name=camiseta&maxPrice")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?name=camiseta&maxPrice=")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "0.00", "-0", "-0.00", "0e0"})
    @WithMockUser
    void zeroMaxPriceIsRejected(String maxPrice) throws Exception {
        search("camiseta", maxPrice).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-0.01", "-50.00", "-9999999999.99", "-1e3"})
    @WithMockUser
    void negativeMaxPriceIsRejected(String maxPrice) throws Exception {
        search("camiseta", maxPrice).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "50,00", "NaN", "Infinity", "$50", "50abc", "abc50", "0x32", "1_000", "50.00.00",
            "1e", "true", " "})
    @WithMockUser
    void nonNumericMaxPriceIsRejected(String maxPrice) throws Exception {
        search("camiseta", maxPrice).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void bothParametersInvalidAreRejected() throws Exception {
        search(" ", "-1").andExpect(status().isBadRequest());
        search("", "abc").andExpect(status().isBadRequest());
        search(" ", "0").andExpect(status().isBadRequest());
    }

    // --- read-only / routing --------------------------------------------------------------------

    @Test
    @WithMockUser
    void queryDoesNotModifyProductsOrTheirData() throws Exception {
        List<String> before = snapshot();

        search("camiseta", "50").andExpect(status().isOk());
        search("camiseta", "1000").andExpect(status().isOk());
        search("camiseta", "0").andExpect(status().isBadRequest());
        search(" ", "50").andExpect(status().isBadRequest());
        search("camiseta", "abc").andExpect(status().isBadRequest());

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
    void routeIsNotTreatedAsASkuAndDoesNotClashWithOtherSearches() throws Exception {
        save("Search By Price", "SEARCH-BY-PRICE", "5.00");

        search("search by", "5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SEARCH-BY-PRICE")));
        mockMvc.perform(get("/api/products/sku/SEARCH-BY-PRICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(5.00));
        mockMvc.perform(get("/api/products/search").param("name", "camiseta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "SUD-001")));
        mockMvc.perform(get("/api/products/search-by-stock").param("name", "camiseta").param("minStock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    @WithMockUser
    void otherExistingEndpointsStillWork() throws Exception {
        mockMvc.perform(get("/api/products/by-max-price").param("price", "35.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "PAN-001")));
        mockMvc.perform(get("/api/products/by-price-range").param("min", "30").param("max", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "PAN-001")));
        mockMvc.perform(get("/api/products/by-min-stock").param("stock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));
        mockMvc.perform(get("/api/products/by-max-stock").param("stock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));
        mockMvc.perform(get("/api/products/by-stock").param("stock", "10"))
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

        mockMvc.perform(post(URL).param("name", "camiseta").param("maxPrice", "50")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL).param("name", "camiseta").param("maxPrice", "50")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(URL).param("name", "camiseta").param("maxPrice", "50"))
                .andExpect(status().isMethodNotAllowed());

        assertThat(snapshot()).isEqualTo(before);
    }

    // --- security -------------------------------------------------------------------------------

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        search("camiseta", "50")
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
        mockMvc.perform(get(URL).param("name", "camiseta").param("maxPrice", "50")
                        .with(httpBasic("nexora_test", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).param("name", "camiseta").param("maxPrice", "50")
                        .with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicWithCorrectCredentialsCanQuery() throws Exception {
        mockMvc.perform(get(URL).param("name", "camiseta").param("maxPrice", "50")
                        .with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }
}
