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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
class ProductOptionalFilterTests {

    private static final String URL = "/api/products/filter-optional";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    /** Inserted in this order, so the ids ascend: CAM-001 < CAM-002 < CAM-003 < SUD-001 < PAN-001 < CAM-004 < ZAP-001. */
    @BeforeEach
    void seedProducts() {
        productRepository.deleteAll();
        save("Camiseta básica", "CAM-001", "19.99", 25);
        save("Camiseta premium", "CAM-002", "89.90", 30);
        save("Camiseta rebajada", "CAM-003", "10.00", 5);
        save("Sudadera camiseta style", "SUD-001", "50.00", 10);
        save("Pantalón cargo", "PAN-001", "35.00", 40);
        save("Camiseta xl", "CAM-004", "50.01", 9);
        save("Zapatillas running", "ZAP-001", "70.00", 0);
    }

    private void save(String name, String sku, String price, int stock) {
        productRepository.save(new Product(name, sku, new BigDecimal(price), stock, 2));
    }

    /** Sends only the parameters passed as name/value pairs. */
    private MockHttpServletRequestBuilder request(String... nameValuePairs) {
        MockHttpServletRequestBuilder builder = get(URL);
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            builder.param(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return builder;
    }

    private ResultActions filter(String... nameValuePairs) throws Exception {
        return mockMvc.perform(request(nameValuePairs));
    }

    // --- no filter / each filter alone ----------------------------------------------------------

    @Test
    @WithMockUser
    void noFilterReturnsEveryProductOrderedByIdAscending() throws Exception {
        filter()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[*].sku",
                        contains("CAM-001", "CAM-002", "CAM-003", "SUD-001", "PAN-001", "CAM-004", "ZAP-001")));
    }

    @Test
    @WithMockUser
    void returnedProductsIncludeAllTheirFields() throws Exception {
        filter("name", "zapatillas")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("ZAP-001"))
                .andExpect(jsonPath("$[0].name").value("Zapatillas running"))
                .andExpect(jsonPath("$[0].price").value(70.00))
                .andExpect(jsonPath("$[0].stock").value(0))
                .andExpect(jsonPath("$[0].minimumStock").value(2))
                .andExpect(jsonPath("$[0].id").isNumber());
    }

    @Test
    @WithMockUser
    void onlyNameFiltersByNameAndIgnoresPriceAndStock() throws Exception {
        filter("name", "camiseta")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "CAM-003", "SUD-001", "CAM-004")));
    }

    @Test
    @WithMockUser
    void onlyMaxPriceFiltersByPriceAndIgnoresNameAndStock() throws Exception {
        filter("maxPrice", "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-003", "SUD-001", "PAN-001")));
    }

    @Test
    @WithMockUser
    void onlyMinStockFiltersByStockAndIgnoresNameAndPrice() throws Exception {
        filter("minStock", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "SUD-001", "PAN-001")));
    }

    // --- pairs and the triple (AND) -------------------------------------------------------------

    @Test
    @WithMockUser
    void nameAndMaxPriceAreCombinedWithAnd() throws Exception {
        filter("name", "camiseta", "maxPrice", "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-003", "SUD-001")));
    }

    @Test
    @WithMockUser
    void nameAndMinStockAreCombinedWithAnd() throws Exception {
        filter("name", "camiseta", "minStock", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "SUD-001")));
    }

    @Test
    @WithMockUser
    void maxPriceAndMinStockAreCombinedWithAnd() throws Exception {
        filter("maxPrice", "50", "minStock", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001", "PAN-001")));
    }

    @Test
    @WithMockUser
    void nameMaxPriceAndMinStockAreCombinedWithAnd() throws Exception {
        filter("name", "camiseta", "maxPrice", "50", "minStock", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")))
                .andExpect(jsonPath("$[*].price", contains(19.99, 50.00)))
                .andExpect(jsonPath("$[*].stock", contains(25, 10)));
    }

    @Test
    @WithMockUser
    void combiningFiltersNeverReturnsMoreThanEachFilterAlone() throws Exception {
        // AND narrows: the triple is a subset of every pair, and every pair a subset of each single filter.
        filter("name", "camiseta", "maxPrice", "50", "minStock", "10")
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
        filter("name", "camiseta", "maxPrice", "50")
                .andExpect(jsonPath("$", hasSize(3)));
        filter("name", "camiseta")
                .andExpect(jsonPath("$", hasSize(5)));
        filter().andExpect(jsonPath("$", hasSize(7)));
    }

    @Test
    @WithMockUser
    void impossibleCombinationReturnsEmptyListEvenWhenEachFilterAloneMatches() throws Exception {
        filter("name", "zapatillas").andExpect(jsonPath("$", hasSize(1)));
        filter("maxPrice", "50").andExpect(jsonPath("$", hasSize(4)));
        filter("minStock", "30").andExpect(jsonPath("$", hasSize(2)));
        // Zapatillas costs 70 (> 50) and has no stock, so no product has all three.
        filter("name", "zapatillas", "maxPrice", "50", "minStock", "1")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
        filter("name", "camiseta", "maxPrice", "20", "minStock", "26")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void sameResultAsTheMandatoryTripleFilterEndpoint() throws Exception {
        String optional = filter("name", "camiseta", "maxPrice", "50", "minStock", "10")
                .andReturn().getResponse().getContentAsString();
        String mandatory = mockMvc.perform(get("/api/products/filter")
                        .param("name", "camiseta").param("maxPrice", "50").param("minStock", "10"))
                .andReturn().getResponse().getContentAsString();

        assertThat(optional).isEqualTo(mandatory);
    }

    // --- inclusive limits -----------------------------------------------------------------------

    @Test
    @WithMockUser
    void priceExactlyEqualToMaxPriceIsIncludedAndJustAboveIsExcluded() throws Exception {
        filter("maxPrice", "50.00").andExpect(jsonPath("$[?(@.sku == 'SUD-001')]").isNotEmpty());
        filter("maxPrice", "50.00").andExpect(jsonPath("$[?(@.sku == 'CAM-004')]").isEmpty());
        filter("maxPrice", "49.99").andExpect(jsonPath("$[?(@.sku == 'SUD-001')]").isEmpty());
        filter("maxPrice", "50.01").andExpect(jsonPath("$[?(@.sku == 'CAM-004')]").isNotEmpty());
    }

    @Test
    @WithMockUser
    void stockExactlyEqualToMinStockIsIncludedAndJustBelowIsExcluded() throws Exception {
        filter("minStock", "10").andExpect(jsonPath("$[?(@.sku == 'SUD-001')]").isNotEmpty());
        filter("minStock", "11").andExpect(jsonPath("$[?(@.sku == 'SUD-001')]").isEmpty());
        filter("minStock", "9").andExpect(jsonPath("$[?(@.sku == 'CAM-004')]").isNotEmpty());
        filter("minStock", "10").andExpect(jsonPath("$[?(@.sku == 'CAM-004')]").isEmpty());
    }

    @Test
    @WithMockUser
    void bothLimitsEqualToTheSameProductStillIncludeIt() throws Exception {
        filter("name", "sudadera", "maxPrice", "50.00", "minStock", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001")));
    }

    // --- minStock = 0 ---------------------------------------------------------------------------

    @Test
    @WithMockUser
    void zeroMinStockIsValidAndDoesNotRestrictTheStock() throws Exception {
        filter("minStock", "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[?(@.sku == 'ZAP-001')]").isNotEmpty());
        filter("minStock", "1").andExpect(jsonPath("$[?(@.sku == 'ZAP-001')]").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "00", "+0", "-0", "0000"})
    @WithMockUser
    void equivalentRepresentationsOfZeroMinStockAreValid(String minStock) throws Exception {
        filter("minStock", minStock).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(7)));
    }

    // --- name: partial / case-insensitive / trimmed ---------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"cam", "iseta", "eta b", "sudadera cam", "style", "básica"})
    @WithMockUser
    void partialNameMatchesAnywhereInTheName(String name) throws Exception {
        filter("name", name).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(org.hamcrest.Matchers.greaterThan(0))));
    }

    @Test
    @WithMockUser
    void partialMatchAtDifferentPositionsReturnsTheRightProducts() throws Exception {
        filter("name", "style").andExpect(jsonPath("$[*].sku", contains("SUD-001")));
        filter("name", "cargo").andExpect(jsonPath("$[*].sku", contains("PAN-001")));
        filter("name", "iseta pre").andExpect(jsonPath("$[*].sku", contains("CAM-002")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"camiseta", "CAMISETA", "CaMiSeTa", "Camiseta"})
    @WithMockUser
    void nameSearchIsCaseInsensitive(String name) throws Exception {
        filter("name", name)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "CAM-003", "SUD-001", "CAM-004")));
    }

    @Test
    @WithMockUser
    void caseInsensitivityAlsoWorksWithAccentedCharacters() throws Exception {
        filter("name", "PANTALÓN").andExpect(jsonPath("$[*].sku", contains("PAN-001")));
        filter("name", "BÁSICA").andExpect(jsonPath("$[*].sku", contains("CAM-001")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"  camiseta", "camiseta  ", "   camiseta   ", "\tcamiseta\t"})
    @WithMockUser
    void leadingAndTrailingWhitespaceInTheNameIsRemoved(String name) throws Exception {
        filter("name", name, "maxPrice", "50", "minStock", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
    }

    @Test
    @WithMockUser
    void internalWhitespaceOfTheNameIsKept() throws Exception {
        filter("name", "  camiseta premium  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "%%", "c_miseta", "cam%eta", "camiseta%", "\\"})
    @WithMockUser
    void likeWildcardsAndBackslashInTheNameAreTakenLiterally(String name) throws Exception {
        filter("name", name).andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void literalPercentAndUnderscoreInNamesCanBeFound() throws Exception {
        save("Algodón 100% Orgánico", "ALG-001", "9.00", 1);
        save("Cinta_Roja", "CIN-001", "2.00", 1);

        filter("name", "100%").andExpect(jsonPath("$[*].sku", contains("ALG-001")));
        filter("name", "a_r").andExpect(jsonPath("$[*].sku", contains("CIN-001")));
    }

    // --- maxPrice: BigDecimal -------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"50", "50.0", "50.00", "50.000", "5e1", "5E+1", "0050.00", "+50.00"})
    @WithMockUser
    void equivalentRepresentationsOfTheSameMaxPriceGiveTheSameResult(String maxPrice) throws Exception {
        filter("maxPrice", maxPrice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-003", "SUD-001", "PAN-001")));
    }

    @Test
    @WithMockUser
    void maxPriceWithMoreDecimalsThanTheColumnIsComparedExactly() throws Exception {
        filter("maxPrice", "49.999").andExpect(jsonPath("$[?(@.sku == 'SUD-001')]").isEmpty());
        filter("maxPrice", "50.001").andExpect(jsonPath("$[?(@.sku == 'SUD-001')]").isNotEmpty());
        filter("maxPrice", "50.009").andExpect(jsonPath("$[?(@.sku == 'CAM-004')]").isEmpty());
    }

    @Test
    @WithMockUser
    void tinyAndVeryLargeMaxPricesBehave() throws Exception {
        filter("maxPrice", "0.0000001").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("maxPrice", "99999999999999999999999.99").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(7)));
    }

    // --- ordering / number of results -----------------------------------------------------------

    @Test
    @WithMockUser
    void resultsAreOrderedByIdAscendingRegardlessOfPriceStockOrName() throws Exception {
        save("Camiseta alfa", "CAM-A", "1.00", 100);
        save("Camiseta zeta", "CAM-Z", "49.00", 11);
        save("Camiseta beta", "CAM-B", "25.00", 50);

        filter("name", "camiseta", "maxPrice", "50", "minStock", "10")
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001", "CAM-A", "CAM-Z", "CAM-B")));
        filter("name", "camiseta")
                .andExpect(jsonPath("$[*].sku",
                        contains("CAM-001", "CAM-002", "CAM-003", "SUD-001", "CAM-004", "CAM-A", "CAM-Z", "CAM-B")));
        filter("maxPrice", "20")
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-003", "CAM-A")));
    }

    @Test
    @WithMockUser
    void idsInTheResponseAreAscending() throws Exception {
        int first = productRepository.findBySku("CAM-001").orElseThrow().getId().intValue();
        int last = productRepository.findBySku("ZAP-001").orElseThrow().getId().intValue();
        assertThat(first).isLessThan(last);

        filter()
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[6].id").value(last));
    }

    @Test
    @WithMockUser
    void singleResultIsReturned() throws Exception {
        filter("name", "sudadera", "maxPrice", "50", "minStock", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("SUD-001"));
    }

    @Test
    @WithMockUser
    void noMatchesReturnsOkWithEmptyList() throws Exception {
        filter("name", "sombrero").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("maxPrice", "0.01").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("minStock", "41").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void emptyCatalogReturnsOkWithEmptyListForAnyCombination() throws Exception {
        productRepository.deleteAll();

        filter().andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("name", "camiseta", "maxPrice", "50", "minStock", "0")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    // --- 400: name present but blank ------------------------------------------------------------

    @Test
    @WithMockUser
    void nameParameterWithoutValueOrEmptyIsRejected() throws Exception {
        // A bare "?name" is not tested: MockMvc delivers it as "absent", whereas a real servlet container
        // delivers an empty string (which would be rejected here like "name=").
        filter("name", "").andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?name=")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?name=&maxPrice=50&minStock=10")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "   ", "\t", " \t \n ", " ", "   "})
    @WithMockUser
    void whitespaceOnlyNameIsRejected(String name) throws Exception {
        filter("name", name).andExpect(status().isBadRequest());
        filter("name", name, "maxPrice", "50", "minStock", "10").andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void blankNameIsRejectedWithAnExplanation() throws Exception {
        filter("name", "  ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").exists());
    }

    // --- 400: maxPrice present but invalid ------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "0.00", "-0", "-0.00", "0e0"})
    @WithMockUser
    void zeroMaxPriceIsRejected(String maxPrice) throws Exception {
        filter("maxPrice", maxPrice).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-0.01", "-50.00", "-9999999999.99", "-1e3"})
    @WithMockUser
    void negativeMaxPriceIsRejected(String maxPrice) throws Exception {
        filter("maxPrice", maxPrice).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "50,00", "NaN", "Infinity", "$50", "50abc", "abc50", "0x32", "1_000", "50.00.00",
            "1e", "true"})
    @WithMockUser
    void nonNumericMaxPriceIsRejected(String maxPrice) throws Exception {
        filter("maxPrice", maxPrice).andExpect(status().isBadRequest());
    }

    // --- 400: minStock present but invalid ------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-2", "-5", "-10", "-100", "-2147483648"})
    @WithMockUser
    void negativeMinStockIsRejected(String minStock) throws Exception {
        filter("minStock", minStock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "10abc", "abc10", "1,0", "NaN", "Infinity", "$10", "1_0", "true", "ten"})
    @WithMockUser
    void nonNumericMinStockIsRejected(String minStock) throws Exception {
        filter("minStock", minStock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.5", "0.5", "10.0", "10.00", "0.0", "1e1", "2.5E0", ".5", "10.", "-0.5"})
    @WithMockUser
    void decimalMinStockIsRejected(String minStock) throws Exception {
        filter("minStock", minStock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "99999999999", "99999999999999999999", "-2147483649",
            "-99999999999999999999"})
    @WithMockUser
    void minStockOutsideTheIntegerRangeIsRejected(String minStock) throws Exception {
        filter("minStock", minStock).andExpect(status().isBadRequest());
    }

    // --- 400: an invalid parameter is never silently ignored ------------------------------------

    @Test
    @WithMockUser
    void oneInvalidParameterRejectsTheRequestEvenIfTheOthersAreValid() throws Exception {
        filter("name", "camiseta", "maxPrice", "0", "minStock", "10").andExpect(status().isBadRequest());
        filter("name", "camiseta", "maxPrice", "50", "minStock", "-1").andExpect(status().isBadRequest());
        filter("name", " ", "maxPrice", "50", "minStock", "10").andExpect(status().isBadRequest());
        filter("name", "camiseta", "maxPrice", "abc").andExpect(status().isBadRequest());
        filter("maxPrice", "-5", "minStock", "abc").andExpect(status().isBadRequest());
    }

    // --- optional parameters: absent, unknown, empty numeric ------------------------------------

    @Test
    @WithMockUser
    void unknownParametersAreIgnored() throws Exception {
        filter("color", "rojo", "page", "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7)));
        filter("name", "camiseta", "foo", "bar", "minStock", "10")
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "SUD-001")));
    }

    /** Spring converts an empty value of a numeric parameter to null, i.e. "not provided". */
    @Test
    @WithMockUser
    void emptyNumericParametersAreTreatedAsAbsent() throws Exception {
        mockMvc.perform(get(URL + "?maxPrice=")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(7)));
        mockMvc.perform(get(URL + "?minStock=")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(7)));
        mockMvc.perform(get(URL + "?name=camiseta&maxPrice=&minStock="))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
    }

    // --- read-only / routing --------------------------------------------------------------------

    @Test
    @WithMockUser
    void queryDoesNotModifyProductsOrTheirData() throws Exception {
        List<String> before = snapshot();

        filter().andExpect(status().isOk());
        filter("name", "camiseta", "maxPrice", "50", "minStock", "10").andExpect(status().isOk());
        filter("minStock", "0").andExpect(status().isOk());
        filter("maxPrice", "0").andExpect(status().isBadRequest());
        filter("name", " ").andExpect(status().isBadRequest());
        filter("minStock", "-1").andExpect(status().isBadRequest());

        assertThat(snapshot()).isEqualTo(before);
        assertThat(productRepository.count()).isEqualTo(7);
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
    void routeIsNotTreatedAsASkuAndDoesNotClashWithTheMandatoryFilter() throws Exception {
        save("Filter Optional", "FILTER-OPTIONAL", "5.00", 3);

        filter("name", "filter optional")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("FILTER-OPTIONAL")));
        mockMvc.perform(get("/api/products/sku/FILTER-OPTIONAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(3));
        // The mandatory /filter endpoint (NXR-020) is untouched: it still requires its three parameters.
        mockMvc.perform(get("/api/products/filter")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/products/filter").param("name", "camiseta").param("maxPrice", "50")
                        .param("minStock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001")));
    }

    @Test
    @WithMockUser
    void otherExistingEndpointsStillWork() throws Exception {
        mockMvc.perform(get("/api/products/search").param("name", "camiseta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
        mockMvc.perform(get("/api/products/search-by-stock").param("name", "camiseta").param("minStock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "SUD-001")));
        mockMvc.perform(get("/api/products/search-by-price").param("name", "camiseta").param("maxPrice", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-003", "SUD-001")));
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
                .andExpect(jsonPath("$[*].sku", contains("CAM-003", "CAM-004", "ZAP-001")));
        mockMvc.perform(get("/api/products/by-stock").param("stock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001")));
        mockMvc.perform(get("/api/products/out-of-stock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("ZAP-001")));
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

        mockMvc.perform(post(URL).param("name", "camiseta").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL).param("name", "camiseta").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(URL).param("name", "camiseta")).andExpect(status().isMethodNotAllowed());

        assertThat(snapshot()).isEqualTo(before);
    }

    // --- security -------------------------------------------------------------------------------

    @Test
    void unauthenticatedRequestIsRejectedWithAndWithoutFilters() throws Exception {
        filter().andExpect(status().isUnauthorized()).andExpect(content().string(""));
        filter("name", "camiseta", "maxPrice", "50", "minStock", "10")
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void unauthenticatedRequestWithInvalidParametersIsStillUnauthorizedNotBadRequest() throws Exception {
        filter("name", " ").andExpect(status().isUnauthorized());
        filter("maxPrice", "0").andExpect(status().isUnauthorized());
        filter("minStock", "-1").andExpect(status().isUnauthorized());
        filter("minStock", "abc").andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        mockMvc.perform(request().with(httpBasic("nexora_test", "wrong"))).andExpect(status().isUnauthorized());
        mockMvc.perform(request("name", "camiseta").with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicWithCorrectCredentialsCanQuery() throws Exception {
        mockMvc.perform(request().with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7)));
        mockMvc.perform(request("name", "camiseta", "maxPrice", "50", "minStock", "10")
                        .with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }
}
