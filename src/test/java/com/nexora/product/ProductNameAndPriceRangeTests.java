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
class ProductNameAndPriceRangeTests {

    private static final String URL = "/api/products/search-by-price-range";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void seedProducts() {
        productRepository.deleteAll();
        save("Camiseta básica", "CAM-001", "19.99");
        save("Camiseta premium", "CAM-002", "20.00");
        save("Sudadera camiseta style", "SUD-001", "35.00");
        save("Camiseta xl", "CAM-003", "50.00");
        save("Camiseta lujo", "CAM-004", "50.01");
        save("Pantalón cargo", "PAN-001", "30.00");
    }

    private void save(String name, String sku, String price) {
        productRepository.save(new Product(name, sku, new BigDecimal(price), 10, 2));
    }

    private ResultActions search(String name, String minPrice, String maxPrice) throws Exception {
        return mockMvc.perform(get(URL).param("name", name).param("minPrice", minPrice).param("maxPrice", maxPrice));
    }

    // --- both conditions (AND) ------------------------------------------------------------------

    @Test
    @WithMockUser
    void productsMatchingNameAndPriceRangeAreReturned() throws Exception {
        search("camiseta", "20", "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].name", contains("Camiseta premium", "Sudadera camiseta style", "Camiseta xl")))
                .andExpect(jsonPath("$[*].price", contains(20.00, 35.00, 50.00)));
    }

    @Test
    @WithMockUser
    void returnedProductsIncludeAllTheirFields() throws Exception {
        search("cargo", "30.00", "30.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("PAN-001"))
                .andExpect(jsonPath("$[0].name").value("Pantalón cargo"))
                .andExpect(jsonPath("$[0].price").value(30.00))
                .andExpect(jsonPath("$[0].stock").value(10))
                .andExpect(jsonPath("$[0].minimumStock").value(2))
                .andExpect(jsonPath("$[0].id").isNumber());
    }

    @Test
    @WithMockUser
    void productMatchingTheNameButOutsideTheRangeIsExcluded() throws Exception {
        search("camiseta", "20", "50")
                .andExpect(jsonPath("$[?(@.sku == 'CAM-001')]").isEmpty())
                .andExpect(jsonPath("$[?(@.sku == 'CAM-004')]").isEmpty());
    }

    @Test
    @WithMockUser
    void productInsideTheRangeButNotMatchingTheNameIsExcluded() throws Exception {
        // Pantalon cargo (30.00) is inside 20-50 but its name does not contain "camiseta".
        search("camiseta", "20", "50").andExpect(jsonPath("$[?(@.sku == 'PAN-001')]").isEmpty());
        search("pantalón", "20", "50").andExpect(jsonPath("$[*].sku", contains("PAN-001")));
    }

    @Test
    @WithMockUser
    void productMatchingNeitherConditionIsExcluded() throws Exception {
        save("Gorra", "GOR-001", "500.00");

        search("camiseta", "20", "50").andExpect(jsonPath("$[?(@.sku == 'GOR-001')]").isEmpty());
    }

    @Test
    @WithMockUser
    void bothConditionsAreCombinedWithAndNotOr() throws Exception {
        // With OR, Pantalon (30.00 in range) would also appear: 4 products instead of 3.
        search("camiseta", "20", "50").andExpect(jsonPath("$", hasSize(3)));
        // With OR, a name that matches nothing would still return every product in the range.
        search("zzz", "20", "50").andExpect(status().isOk()).andExpect(content().string("[]"));
        // With OR, a range that matches nothing would still return every product whose name matches.
        search("camiseta", "60", "70").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- inclusive limits / minPrice == maxPrice ------------------------------------------------

    @Test
    @WithMockUser
    void productEqualToMinPriceIsIncluded() throws Exception {
        search("camiseta", "20.00", "30.00").andExpect(jsonPath("$[*].sku", contains("CAM-002")));
        search("camiseta", "19.99", "19.99").andExpect(jsonPath("$[*].sku", contains("CAM-001")));
    }

    @Test
    @WithMockUser
    void productEqualToMaxPriceIsIncluded() throws Exception {
        search("camiseta", "30.00", "50.00").andExpect(jsonPath("$[*].sku", contains("SUD-001", "CAM-003")));
        search("camiseta", "50.00", "50.00").andExpect(jsonPath("$[*].sku", contains("CAM-003")));
    }

    @Test
    @WithMockUser
    void productsJustOutsideTheLimitsAreExcluded() throws Exception {
        search("camiseta", "20.01", "49.99").andExpect(jsonPath("$[*].sku", contains("SUD-001")));
        search("camiseta", "19.99", "50.00").andExpect(jsonPath("$", hasSize(4)));
        search("camiseta", "50.01", "50.01").andExpect(jsonPath("$[*].sku", contains("CAM-004")));
        search("camiseta", "20.01", "34.99").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void minPriceEqualToMaxPriceIsAValidRangeForAnExactPrice() throws Exception {
        search("camiseta", "35.00", "35.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001")));
        search("camiseta", "40.00", "40.00").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void equalLimitsWithDifferentScalesAreAValidRange() throws Exception {
        search("camiseta", "50", "50.00").andExpect(status().isOk()).andExpect(jsonPath("$[*].sku", contains("CAM-003")));
        search("camiseta", "5e1", "50.0").andExpect(status().isOk()).andExpect(jsonPath("$[*].sku", contains("CAM-003")));
    }

    // --- ordering / number of results -----------------------------------------------------------

    @Test
    @WithMockUser
    void resultsAreOrderedByIdAscendingRegardlessOfPriceOrName() throws Exception {
        save("Camiseta barata", "CAM-A", "21.00");
        save("Camiseta cara", "CAM-B", "49.00");
        save("Camiseta media", "CAM-C", "30.00");

        search("camiseta", "20", "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "SUD-001", "CAM-003", "CAM-A", "CAM-B", "CAM-C")));
    }

    @Test
    @WithMockUser
    void idsInTheResponseAreAscending() throws Exception {
        save("Camiseta barata", "CAM-A", "21.00");
        int first = productRepository.findBySku("CAM-002").orElseThrow().getId().intValue();
        int second = productRepository.findBySku("SUD-001").orElseThrow().getId().intValue();
        int fourth = productRepository.findBySku("CAM-A").orElseThrow().getId().intValue();
        assertThat(first).isLessThan(second);
        assertThat(second).isLessThan(fourth);

        // CAM-A is the second cheapest but was inserted last, so ordering by id puts it last, not first.
        search("camiseta", "20", "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[1].id").value(second))
                .andExpect(jsonPath("$[3].id").value(fourth));
    }

    @Test
    @WithMockUser
    void singleResultIsReturned() throws Exception {
        search("sudadera", "20", "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("SUD-001"));
    }

    @Test
    @WithMockUser
    void noMatchesReturnsOkWithEmptyList() throws Exception {
        search("sombrero", "1", "1000").andExpect(status().isOk()).andExpect(content().string("[]"));
        search("camiseta", "21", "34").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void emptyCatalogReturnsOkWithEmptyList() throws Exception {
        productRepository.deleteAll();

        search("camiseta", "20", "50").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- name: partial / case-insensitive / trimmed ---------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"cam", "iseta", "eta p", "sudadera cam", "style", "premium"})
    @WithMockUser
    void partialNameMatchesAnywhereInTheName(String name) throws Exception {
        search(name, "1", "1000").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(greaterThan(0))));
    }

    @Test
    @WithMockUser
    void partialMatchAtDifferentPositionsReturnsTheRightProducts() throws Exception {
        search("style", "1", "1000").andExpect(jsonPath("$[*].sku", contains("SUD-001")));
        search("cargo", "1", "1000").andExpect(jsonPath("$[*].sku", contains("PAN-001")));
        search("iseta pre", "1", "1000").andExpect(jsonPath("$[*].sku", contains("CAM-002")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"camiseta", "CAMISETA", "CaMiSeTa", "Camiseta"})
    @WithMockUser
    void nameSearchIsCaseInsensitive(String name) throws Exception {
        search(name, "20", "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "SUD-001", "CAM-003")));
    }

    @Test
    @WithMockUser
    void caseInsensitivityAlsoWorksWithAccentedCharacters() throws Exception {
        search("PANTALÓN", "1", "1000").andExpect(jsonPath("$[*].sku", contains("PAN-001")));
        search("BÁSICA", "1", "1000").andExpect(jsonPath("$[*].sku", contains("CAM-001")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"  camiseta", "camiseta  ", "   camiseta   ", "\tcamiseta\t"})
    @WithMockUser
    void leadingAndTrailingWhitespaceInTheNameIsRemoved(String name) throws Exception {
        search(name, "20", "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "SUD-001", "CAM-003")));
    }

    @Test
    @WithMockUser
    void internalWhitespaceOfTheNameIsKept() throws Exception {
        search("  camiseta premium  ", "1", "1000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "%%", "c_miseta", "cam%eta", "camiseta%"})
    @WithMockUser
    void likeWildcardsInTheNameAreTakenLiterally(String name) throws Exception {
        search(name, "1", "1000").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- BigDecimal / decimals ------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"20, 50", "20.0, 50.0", "20.00, 50.00", "20.000, 50.000", "2e1, 5e1", "020.00, 050.00",
            "+20.00, +50.00"})
    @WithMockUser
    void equivalentRepresentationsOfTheSameRangeGiveTheSameResult(String min, String max) throws Exception {
        search("camiseta", min, max)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "SUD-001", "CAM-003")));
    }

    @Test
    @WithMockUser
    void limitsWithMoreDecimalsThanTheColumnAreComparedExactly() throws Exception {
        search("camiseta", "19.995", "20.005").andExpect(jsonPath("$[*].sku", contains("CAM-002")));
        search("camiseta", "19.9899", "19.9901").andExpect(jsonPath("$[*].sku", contains("CAM-001")));
        search("camiseta", "50.005", "50.009").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void tinyAndVeryLargeLimitsBehave() throws Exception {
        search("camiseta", "0.0000001", "0.0000002").andExpect(status().isOk()).andExpect(content().string("[]"));
        search("camiseta", "0.01", "99999999999999999999999.99")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
    }

    // --- 400: name ------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void missingNameIsRejected() throws Exception {
        mockMvc.perform(get(URL).param("minPrice", "20").param("maxPrice", "50"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void emptyNameIsRejected() throws Exception {
        search("", "20", "50").andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?name&minPrice=20&maxPrice=50")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "   ", "\t", " \t \n "})
    @WithMockUser
    void whitespaceOnlyNameIsRejected(String name) throws Exception {
        search(name, "20", "50").andExpect(status().isBadRequest());
    }

    // --- 400: minPrice / maxPrice ---------------------------------------------------------------

    @Test
    @WithMockUser
    void missingMinPriceIsRejected() throws Exception {
        mockMvc.perform(get(URL).param("name", "camiseta").param("maxPrice", "50"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void missingMaxPriceIsRejected() throws Exception {
        mockMvc.perform(get(URL).param("name", "camiseta").param("minPrice", "20"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void missingBothPricesOrAllParametersIsRejected() throws Exception {
        mockMvc.perform(get(URL).param("name", "camiseta")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL)).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("name", "camiseta").param("min", "20").param("max", "50"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void pricesWithoutValueAreRejected() throws Exception {
        mockMvc.perform(get(URL + "?name=camiseta&minPrice&maxPrice=50")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?name=camiseta&minPrice=20&maxPrice")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?name=camiseta&minPrice=&maxPrice=")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "20,00", "NaN", "Infinity", "$20", "20abc", "abc20", "0x14", "1_000", "20.00.00",
            "1e", "true"})
    @WithMockUser
    void nonNumericMinPriceIsRejected(String minPrice) throws Exception {
        search("camiseta", minPrice, "50").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "50,00", "NaN", "Infinity", "$50", "50abc", "abc50", "0x32", "1_000", "50.00.00",
            "1e", "true"})
    @WithMockUser
    void nonNumericMaxPriceIsRejected(String maxPrice) throws Exception {
        search("camiseta", "20", maxPrice).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "0.00", "-0", "-0.00", "0e0"})
    @WithMockUser
    void zeroMinPriceIsRejected(String minPrice) throws Exception {
        search("camiseta", minPrice, "50").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "0.00", "-0", "-0.00", "0e0"})
    @WithMockUser
    void zeroMaxPriceIsRejected(String maxPrice) throws Exception {
        search("camiseta", "20", maxPrice).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-0.01", "-20.00", "-9999999999.99", "-1e3"})
    @WithMockUser
    void negativeMinPriceIsRejected(String minPrice) throws Exception {
        search("camiseta", minPrice, "50").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-0.01", "-50.00", "-9999999999.99", "-1e3"})
    @WithMockUser
    void negativeMaxPriceIsRejected(String maxPrice) throws Exception {
        search("camiseta", "20", maxPrice).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void severalInvalidParametersAtOnceAreRejected() throws Exception {
        search(" ", "-1", "0").andExpect(status().isBadRequest());
        search("", "abc", "xyz").andExpect(status().isBadRequest());
    }

    // --- 400: minPrice > maxPrice ---------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"50.01, 50.00", "50.00, 20.00", "100, 99.99", "0.02, 0.01", "50.001, 50.000", "5e1, 49.99",
            "9999999999.99, 0.01"})
    @WithMockUser
    void minPriceGreaterThanMaxPriceIsRejectedWithAnExplanation(String min, String max) throws Exception {
        search("camiseta", min, max)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", containsString("min")))
                .andExpect(jsonPath("$.detail", containsString("max")));
    }

    @Test
    @WithMockUser
    void invalidRangeReportsTheSameErrorAsThePlainPriceRangeEndpoint() throws Exception {
        String expected = mockMvc.perform(get("/api/products/by-price-range").param("min", "50").param("max", "20"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        String actual = search("camiseta", "50", "20")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(actual).contains("min (50) must be less than or equal to max (20)");
        assertThat(expected).contains("min (50) must be less than or equal to max (20)");
    }

    // --- read-only / routing --------------------------------------------------------------------

    @Test
    @WithMockUser
    void queryDoesNotModifyProductsOrTheirData() throws Exception {
        List<String> before = snapshot();

        search("camiseta", "20", "50").andExpect(status().isOk());
        search("camiseta", "35", "35").andExpect(status().isOk());
        search("camiseta", "50", "20").andExpect(status().isBadRequest());
        search("camiseta", "0", "50").andExpect(status().isBadRequest());
        search(" ", "20", "50").andExpect(status().isBadRequest());

        assertThat(snapshot()).isEqualTo(before);
        assertThat(productRepository.count()).isEqualTo(6);
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
    void routeIsNotTreatedAsASkuAndDoesNotClashWithTheOtherSearches() throws Exception {
        save("Search Range", "SEARCH-RANGE", "5.00");

        search("search range", "5", "5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SEARCH-RANGE")));
        mockMvc.perform(get("/api/products/sku/SEARCH-RANGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(5.00));
        mockMvc.perform(get("/api/products/search-by-price").param("name", "camiseta").param("maxPrice", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002")));
        mockMvc.perform(get("/api/products/search").param("name", "camiseta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
    }

    @Test
    @WithMockUser
    void otherExistingEndpointsStillWork() throws Exception {
        mockMvc.perform(get("/api/products/by-price-range").param("min", "20").param("max", "35"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "SUD-001", "PAN-001")));
        mockMvc.perform(get("/api/products/by-max-price").param("price", "20.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002")));
        mockMvc.perform(get("/api/products/search-by-stock").param("name", "camiseta").param("minStock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
        mockMvc.perform(get("/api/products/search-by-max-stock").param("name", "camiseta").param("maxStock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
        mockMvc.perform(get("/api/products/search-by-exact-stock").param("name", "camiseta").param("stock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
        mockMvc.perform(get("/api/products/by-min-stock").param("stock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)));
        mockMvc.perform(get("/api/products/by-max-stock").param("stock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)));
        mockMvc.perform(get("/api/products/by-stock").param("stock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)));
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

        mockMvc.perform(post(URL).param("name", "camiseta").param("minPrice", "20").param("maxPrice", "50")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL).param("name", "camiseta").param("minPrice", "20").param("maxPrice", "50")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(URL).param("name", "camiseta").param("minPrice", "20").param("maxPrice", "50"))
                .andExpect(status().isMethodNotAllowed());

        assertThat(snapshot()).isEqualTo(before);
    }

    // --- security -------------------------------------------------------------------------------

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        search("camiseta", "20", "50")
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void unauthenticatedRequestWithInvalidParametersIsStillUnauthorizedNotBadRequest() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        search(" ", "-1", "0").andExpect(status().isUnauthorized());
        search("camiseta", "50", "20").andExpect(status().isUnauthorized());
        search("camiseta", "abc", "50").andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        mockMvc.perform(get(URL).param("name", "camiseta").param("minPrice", "20").param("maxPrice", "50")
                        .with(httpBasic("nexora_test", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).param("name", "camiseta").param("minPrice", "20").param("maxPrice", "50")
                        .with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicWithCorrectCredentialsCanQuery() throws Exception {
        mockMvc.perform(get(URL).param("name", "camiseta").param("minPrice", "20").param("maxPrice", "50")
                        .with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }
}
