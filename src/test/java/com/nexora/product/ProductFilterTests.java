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
class ProductFilterTests {

    private static final String URL = "/api/products/filter";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    /**
     * With name=camiseta, maxPrice=50 and minStock=10, each product below fails exactly the conditions noted;
     * only CAM-001 and SUD-001 satisfy all three.
     */
    @BeforeEach
    void seedProducts() {
        productRepository.deleteAll();
        save("Camiseta básica", "CAM-001", "19.99", 25);          // matches all three
        save("Camiseta premium", "CAM-002", "89.90", 30);              // fails price
        save("Camiseta rebajada", "CAM-003", "10.00", 5);              // fails stock
        save("Sudadera camiseta style", "SUD-001", "50.00", 10);       // matches, both limits exactly
        save("Pantalón cargo", "PAN-001", "35.00", 40);           // fails name
        save("Camiseta xl", "CAM-004", "50.01", 9);                    // fails price and stock
    }

    private void save(String name, String sku, String price, int stock) {
        productRepository.save(new Product(name, sku, new BigDecimal(price), stock, 2));
    }

    private ResultActions filter(String name, String maxPrice, String minStock) throws Exception {
        return mockMvc.perform(get(URL).param("name", name).param("maxPrice", maxPrice).param("minStock", minStock));
    }

    // --- the three conditions (AND) -------------------------------------------------------------

    @Test
    @WithMockUser
    void productsMeetingAllThreeConditionsAreReturned() throws Exception {
        filter("camiseta", "50", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", contains("Camiseta básica", "Sudadera camiseta style")))
                .andExpect(jsonPath("$[*].price", contains(19.99, 50.00)))
                .andExpect(jsonPath("$[*].stock", contains(25, 10)));
    }

    @Test
    @WithMockUser
    void returnedProductsIncludeAllTheirFields() throws Exception {
        filter("básica", "20", "25")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("CAM-001"))
                .andExpect(jsonPath("$[0].name").value("Camiseta básica"))
                .andExpect(jsonPath("$[0].price").value(19.99))
                .andExpect(jsonPath("$[0].stock").value(25))
                .andExpect(jsonPath("$[0].minimumStock").value(2))
                .andExpect(jsonPath("$[0].id").isNumber());
    }

    @Test
    @WithMockUser
    void aProductFailingOnlyThePriceIsExcluded() throws Exception {
        filter("camiseta", "50", "10").andExpect(jsonPath("$[?(@.sku == 'CAM-002')]").isEmpty());
    }

    @Test
    @WithMockUser
    void aProductFailingOnlyTheStockIsExcluded() throws Exception {
        filter("camiseta", "50", "10").andExpect(jsonPath("$[?(@.sku == 'CAM-003')]").isEmpty());
    }

    @Test
    @WithMockUser
    void aProductFailingOnlyTheNameIsExcluded() throws Exception {
        filter("camiseta", "50", "10").andExpect(jsonPath("$[?(@.sku == 'PAN-001')]").isEmpty());
        filter("pantalón", "50", "10").andExpect(jsonPath("$[*].sku", contains("PAN-001")));
    }

    @Test
    @WithMockUser
    void aProductFailingPriceAndStockIsExcluded() throws Exception {
        filter("camiseta", "50", "10").andExpect(jsonPath("$[?(@.sku == 'CAM-004')]").isEmpty());
    }

    @Test
    @WithMockUser
    void aProductMeetingNoConditionIsExcluded() throws Exception {
        save("Gorra", "GOR-001", "500.00", 1);

        filter("camiseta", "50", "10").andExpect(jsonPath("$[?(@.sku == 'GOR-001')]").isEmpty());
    }

    @Test
    @WithMockUser
    void allThreeConditionsAreCombinedWithAndNotOr() throws Exception {
        // With any OR, products failing one condition (CAM-002, CAM-003, PAN-001...) would also appear.
        filter("camiseta", "50", "10").andExpect(jsonPath("$", hasSize(2)));
        // Relaxing one condition at a time brings in exactly the product that failed only that condition.
        filter("camiseta", "100", "10").andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "SUD-001")));
        filter("camiseta", "50", "0").andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-003", "SUD-001")));
        filter("a", "50", "10").andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001", "PAN-001")));
        // Making any single condition impossible empties the result, even though the other two are met.
        filter("zzz", "1000", "0").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("camiseta", "0.01", "0").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("camiseta", "1000", "1000").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- inclusive limits -----------------------------------------------------------------------

    @Test
    @WithMockUser
    void priceExactlyEqualToMaxPriceIsIncluded() throws Exception {
        filter("camiseta", "50.00", "10").andExpect(jsonPath("$[?(@.sku == 'SUD-001')]").isNotEmpty());
        filter("camiseta", "19.99", "10").andExpect(jsonPath("$[*].sku", contains("CAM-001")));
    }

    @Test
    @WithMockUser
    void priceJustAboveMaxPriceIsExcluded() throws Exception {
        filter("camiseta", "49.99", "10").andExpect(jsonPath("$[*].sku", contains("CAM-001")));
        filter("camiseta", "19.98", "10").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void stockExactlyEqualToMinStockIsIncluded() throws Exception {
        filter("camiseta", "50", "10").andExpect(jsonPath("$[?(@.sku == 'SUD-001')]").isNotEmpty());
        filter("camiseta", "50", "25").andExpect(jsonPath("$[*].sku", contains("CAM-001")));
    }

    @Test
    @WithMockUser
    void stockJustBelowMinStockIsExcluded() throws Exception {
        filter("camiseta", "50", "11").andExpect(jsonPath("$[*].sku", contains("CAM-001")));
        filter("camiseta", "50", "26").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void bothLimitsEqualToTheSameProductStillIncludeIt() throws Exception {
        filter("camiseta premium", "89.90", "30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002")));
    }

    // --- minStock = 0 ---------------------------------------------------------------------------

    @Test
    @WithMockUser
    void zeroMinStockIsValidAndDoesNotFilterByStock() throws Exception {
        save("Camiseta agotada", "CAM-OUT", "5.00", 0);

        filter("camiseta", "50", "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-003", "SUD-001", "CAM-OUT")));
        filter("camiseta", "50", "1").andExpect(jsonPath("$[?(@.sku == 'CAM-OUT')]").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "00", "+0", "-0", "0000"})
    @WithMockUser
    void equivalentRepresentationsOfZeroMinStockAreValid(String minStock) throws Exception {
        filter("camiseta", "50", minStock).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)));
    }

    // --- ordering / number of results -----------------------------------------------------------

    @Test
    @WithMockUser
    void resultsAreOrderedByIdAscendingRegardlessOfPriceStockOrName() throws Exception {
        save("Camiseta alfa", "CAM-A", "1.00", 100);
        save("Camiseta zeta", "CAM-Z", "49.00", 11);
        save("Camiseta beta", "CAM-B", "25.00", 50);

        filter("camiseta", "50", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001", "CAM-A", "CAM-Z", "CAM-B")));
    }

    @Test
    @WithMockUser
    void idsInTheResponseAreAscending() throws Exception {
        save("Camiseta alfa", "CAM-A", "1.00", 100);
        int first = productRepository.findBySku("CAM-001").orElseThrow().getId().intValue();
        int second = productRepository.findBySku("SUD-001").orElseThrow().getId().intValue();
        int third = productRepository.findBySku("CAM-A").orElseThrow().getId().intValue();
        assertThat(first).isLessThan(second);
        assertThat(second).isLessThan(third);

        // CAM-A is the cheapest and has the most stock but was inserted last, so ordering by id puts it last.
        filter("camiseta", "50", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[1].id").value(second))
                .andExpect(jsonPath("$[2].id").value(third));
    }

    @Test
    @WithMockUser
    void singleResultIsReturned() throws Exception {
        filter("sudadera", "50", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("SUD-001"));
    }

    @Test
    @WithMockUser
    void noMatchesReturnsOkWithEmptyList() throws Exception {
        filter("sombrero", "1000", "0").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("camiseta", "50", "26").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void emptyCatalogReturnsOkWithEmptyList() throws Exception {
        productRepository.deleteAll();

        filter("camiseta", "50", "10").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- name: partial / case-insensitive / trimmed ---------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"cam", "iseta", "eta b", "sudadera cam", "style", "básica"})
    @WithMockUser
    void partialNameMatchesAnywhereInTheName(String name) throws Exception {
        filter(name, "1000", "0").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(greaterThan(0))));
    }

    @Test
    @WithMockUser
    void partialMatchAtDifferentPositionsReturnsTheRightProducts() throws Exception {
        filter("style", "1000", "0").andExpect(jsonPath("$[*].sku", contains("SUD-001")));
        filter("cargo", "1000", "0").andExpect(jsonPath("$[*].sku", contains("PAN-001")));
        filter("iseta pre", "1000", "0").andExpect(jsonPath("$[*].sku", contains("CAM-002")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"camiseta", "CAMISETA", "CaMiSeTa", "Camiseta"})
    @WithMockUser
    void nameSearchIsCaseInsensitive(String name) throws Exception {
        filter(name, "50", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
    }

    @Test
    @WithMockUser
    void caseInsensitivityAlsoWorksWithAccentedCharacters() throws Exception {
        filter("PANTALÓN", "1000", "0").andExpect(jsonPath("$[*].sku", contains("PAN-001")));
        filter("BÁSICA", "1000", "0").andExpect(jsonPath("$[*].sku", contains("CAM-001")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"  camiseta", "camiseta  ", "   camiseta   ", "\tcamiseta\t"})
    @WithMockUser
    void leadingAndTrailingWhitespaceInTheNameIsRemoved(String name) throws Exception {
        filter(name, "50", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
    }

    @Test
    @WithMockUser
    void internalWhitespaceOfTheNameIsKept() throws Exception {
        filter("  camiseta premium  ", "1000", "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "%%", "c_miseta", "cam%eta", "camiseta%"})
    @WithMockUser
    void likeWildcardsInTheNameAreTakenLiterally(String name) throws Exception {
        filter(name, "1000", "0").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- maxPrice: BigDecimal -------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"50", "50.0", "50.00", "50.000", "5e1", "5E+1", "0050.00", "+50.00"})
    @WithMockUser
    void equivalentRepresentationsOfTheSameMaxPriceGiveTheSameResult(String maxPrice) throws Exception {
        filter("camiseta", maxPrice, "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
    }

    @Test
    @WithMockUser
    void maxPriceWithMoreDecimalsThanTheColumnIsComparedExactly() throws Exception {
        filter("camiseta", "49.999", "10").andExpect(jsonPath("$[*].sku", contains("CAM-001")));
        filter("camiseta", "50.001", "10").andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
        filter("camiseta", "19.9899", "10").andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void tinyAndVeryLargeLimitsBehave() throws Exception {
        filter("camiseta", "0.0000001", "0").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("camiseta", "99999999999999999999999.99", "2147483647")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
        filter("camiseta", "99999999999999999999999.99", "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
    }

    // --- 400: name ------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void missingNameIsRejected() throws Exception {
        mockMvc.perform(get(URL).param("maxPrice", "50").param("minStock", "10"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void emptyNameIsRejected() throws Exception {
        filter("", "50", "10").andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?name&maxPrice=50&minStock=10")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "   ", "\t", " \t \n "})
    @WithMockUser
    void whitespaceOnlyNameIsRejected(String name) throws Exception {
        filter(name, "50", "10").andExpect(status().isBadRequest());
    }

    // --- 400: maxPrice --------------------------------------------------------------------------

    @Test
    @WithMockUser
    void missingMaxPriceIsRejected() throws Exception {
        mockMvc.perform(get(URL).param("name", "camiseta").param("minStock", "10"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void maxPriceWithoutValueIsRejected() throws Exception {
        mockMvc.perform(get(URL + "?name=camiseta&maxPrice&minStock=10")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?name=camiseta&maxPrice=&minStock=10")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "0.00", "-0", "-0.00", "0e0"})
    @WithMockUser
    void zeroMaxPriceIsRejected(String maxPrice) throws Exception {
        filter("camiseta", maxPrice, "10").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-0.01", "-50.00", "-9999999999.99", "-1e3"})
    @WithMockUser
    void negativeMaxPriceIsRejected(String maxPrice) throws Exception {
        filter("camiseta", maxPrice, "10").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "50,00", "NaN", "Infinity", "$50", "50abc", "abc50", "0x32", "1_000", "50.00.00",
            "1e", "true", " "})
    @WithMockUser
    void nonNumericMaxPriceIsRejected(String maxPrice) throws Exception {
        filter("camiseta", maxPrice, "10").andExpect(status().isBadRequest());
    }

    // --- 400: minStock --------------------------------------------------------------------------

    @Test
    @WithMockUser
    void missingMinStockIsRejected() throws Exception {
        mockMvc.perform(get(URL).param("name", "camiseta").param("maxPrice", "50"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void minStockWithoutValueIsRejected() throws Exception {
        mockMvc.perform(get(URL + "?name=camiseta&maxPrice=50&minStock")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?name=camiseta&maxPrice=50&minStock=")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-2", "-5", "-10", "-100", "-2147483648"})
    @WithMockUser
    void negativeMinStockIsRejected(String minStock) throws Exception {
        filter("camiseta", "50", minStock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "10abc", "abc10", "1,0", "NaN", "Infinity", "$10", "1_0", "true", "ten", " "})
    @WithMockUser
    void nonNumericMinStockIsRejected(String minStock) throws Exception {
        filter("camiseta", "50", minStock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.5", "0.5", "10.0", "10.00", "0.0", "1e1", "2.5E0", ".5", "10.", "-0.5"})
    @WithMockUser
    void decimalMinStockIsRejected(String minStock) throws Exception {
        filter("camiseta", "50", minStock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "99999999999", "99999999999999999999", "-2147483649",
            "-99999999999999999999"})
    @WithMockUser
    void minStockOutsideTheIntegerRangeIsRejected(String minStock) throws Exception {
        filter("camiseta", "50", minStock).andExpect(status().isBadRequest());
    }

    // --- 400: combinations ----------------------------------------------------------------------

    @Test
    @WithMockUser
    void allParametersMissingOrOnlyUnknownOnesAreRejected() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("name", "camiseta")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("price", "50").param("stock", "10")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void severalInvalidParametersAtOnceAreRejected() throws Exception {
        filter(" ", "0", "-1").andExpect(status().isBadRequest());
        filter("", "abc", "xyz").andExpect(status().isBadRequest());
        filter("camiseta", "-5", "-5").andExpect(status().isBadRequest());
    }

    // --- read-only / routing --------------------------------------------------------------------

    @Test
    @WithMockUser
    void queryDoesNotModifyProductsOrTheirData() throws Exception {
        List<String> before = snapshot();

        filter("camiseta", "50", "10").andExpect(status().isOk());
        filter("camiseta", "50", "0").andExpect(status().isOk());
        filter("camiseta", "0", "10").andExpect(status().isBadRequest());
        filter("camiseta", "50", "-1").andExpect(status().isBadRequest());
        filter(" ", "50", "10").andExpect(status().isBadRequest());

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
    void routeIsNotTreatedAsASkuAndDoesNotClashWithOtherEndpoints() throws Exception {
        save("Filter Item", "FILTER", "5.00", 3);

        filter("filter item", "5", "3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("FILTER")));
        mockMvc.perform(get("/api/products/sku/FILTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(3));
        mockMvc.perform(get("/api/products/search").param("name", "filter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @WithMockUser
    void previousCombinedSearchesStillWorkAndDifferFromTheTripleFilter() throws Exception {
        // Same name, one condition at a time (NXR-015 / NXR-016), versus all three together.
        mockMvc.perform(get("/api/products/search-by-stock").param("name", "camiseta").param("minStock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "SUD-001")));
        mockMvc.perform(get("/api/products/search-by-price").param("name", "camiseta").param("maxPrice", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-003", "SUD-001")));
        filter("camiseta", "50", "10").andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
        mockMvc.perform(get("/api/products/search-by-max-stock").param("name", "camiseta").param("maxStock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-003", "SUD-001", "CAM-004")));
        mockMvc.perform(get("/api/products/search-by-exact-stock").param("name", "camiseta").param("stock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001")));
        mockMvc.perform(get("/api/products/search-by-price-range").param("name", "camiseta")
                        .param("minPrice", "20").param("maxPrice", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001")));
    }

    @Test
    @WithMockUser
    void otherExistingEndpointsStillWork() throws Exception {
        mockMvc.perform(get("/api/products/by-max-price").param("price", "20.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-003")));
        mockMvc.perform(get("/api/products/by-price-range").param("min", "30").param("max", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "PAN-001")));
        mockMvc.perform(get("/api/products/by-min-stock").param("stock", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "PAN-001")));
        mockMvc.perform(get("/api/products/by-max-stock").param("stock", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-003", "CAM-004")));
        mockMvc.perform(get("/api/products/by-stock").param("stock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001")));
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

        mockMvc.perform(post(URL).param("name", "camiseta").param("maxPrice", "50").param("minStock", "10")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL).param("name", "camiseta").param("maxPrice", "50").param("minStock", "10")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(URL).param("name", "camiseta").param("maxPrice", "50").param("minStock", "10"))
                .andExpect(status().isMethodNotAllowed());

        assertThat(snapshot()).isEqualTo(before);
    }

    // --- security -------------------------------------------------------------------------------

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        filter("camiseta", "50", "10")
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void unauthenticatedRequestWithInvalidParametersIsStillUnauthorizedNotBadRequest() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        filter(" ", "0", "-1").andExpect(status().isUnauthorized());
        filter("camiseta", "abc", "10").andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        mockMvc.perform(get(URL).param("name", "camiseta").param("maxPrice", "50").param("minStock", "10")
                        .with(httpBasic("nexora_test", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).param("name", "camiseta").param("maxPrice", "50").param("minStock", "10")
                        .with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicWithCorrectCredentialsCanQuery() throws Exception {
        mockMvc.perform(get(URL).param("name", "camiseta").param("maxPrice", "50").param("minStock", "10")
                        .with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }
}
