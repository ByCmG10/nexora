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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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

/**
 * NXR-023: the optional filter endpoint gains maxStock. Runs against the isolated in-memory database from
 * src/test/resources/application.properties.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductOptionalFilterMaxStockTests {

    private static final String URL = "/api/products/filter-optional";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    /** Inserted in this order, so the ids ascend: CAM-001 < CAM-002 < CAM-003 < SUD-001 < PAN-001 < CAM-004 < ZAP-001 < CAM-005. */
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
        save("Camiseta oferta", "CAM-005", "20.00", 12);
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

    // --- maxStock alone / with each other filter ------------------------------------------------

    @Test
    @WithMockUser
    void onlyMaxStockFiltersByStockAndIgnoresNameAndPrice() throws Exception {
        filter("maxStock", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-003", "SUD-001", "CAM-004", "ZAP-001")))
                .andExpect(jsonPath("$[*].stock", contains(5, 10, 9, 0)));
    }

    @Test
    @WithMockUser
    void returnedProductsIncludeAllTheirFields() throws Exception {
        filter("maxStock", "0")
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
    void minStockAndMaxStockDefineAnInclusiveStockRange() throws Exception {
        filter("minStock", "10", "maxStock", "30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "SUD-001", "CAM-005")))
                .andExpect(jsonPath("$[*].stock", contains(25, 30, 10, 12)));
    }

    @Test
    @WithMockUser
    void nameAndMaxStockAreCombinedWithAnd() throws Exception {
        filter("name", "camiseta", "maxStock", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-003", "SUD-001", "CAM-004")));
    }

    @Test
    @WithMockUser
    void priceFiltersAndMaxStockAreCombinedWithAnd() throws Exception {
        filter("minPrice", "20", "maxPrice", "50", "maxStock", "12")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "CAM-005")));
        filter("minPrice", "20", "maxStock", "9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-004", "ZAP-001")));
        filter("maxPrice", "20", "maxStock", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-003")));
    }

    @Test
    @WithMockUser
    void allFiveFiltersAreCombinedWithAnd() throws Exception {
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "10", "maxStock", "30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "CAM-005")));
        // maxStock=11 removes CAM-005 (stock 12); minStock=11 removes SUD-001 (stock 10).
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "10", "maxStock", "11")
                .andExpect(jsonPath("$[*].sku", contains("SUD-001")));
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "11", "maxStock", "30")
                .andExpect(jsonPath("$[*].sku", contains("CAM-005")));
    }

    @Test
    @WithMockUser
    void priceRangeAndStockRangeWorkSimultaneously() throws Exception {
        filter("minPrice", "20", "maxPrice", "50", "minStock", "10", "maxStock", "30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "CAM-005")));
        // PAN-001 is inside the price range (35.00) but outside the stock range (40); CAM-002 the opposite.
        filter("minPrice", "20", "maxPrice", "50", "minStock", "10", "maxStock", "30")
                .andExpect(jsonPath("$[?(@.sku == 'PAN-001')]").isEmpty())
                .andExpect(jsonPath("$[?(@.sku == 'CAM-002')]").isEmpty());
    }

    @Test
    @WithMockUser
    void eachFilterNarrowsTheResultWhenAdded() throws Exception {
        filter().andExpect(jsonPath("$", hasSize(8)));
        filter("name", "camiseta").andExpect(jsonPath("$", hasSize(6)));
        filter("name", "camiseta", "minPrice", "20").andExpect(jsonPath("$", hasSize(4)));
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50").andExpect(jsonPath("$", hasSize(2)));
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "10")
                .andExpect(jsonPath("$", hasSize(2)));
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "10", "maxStock", "11")
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @WithMockUser
    void combinationsAreAndNotOr() throws Exception {
        // With OR, any product failing a single condition would appear (CAM-003 has stock 5 but price 10).
        filter("name", "camiseta", "minPrice", "20", "maxStock", "10")
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "CAM-004")))
                .andExpect(jsonPath("$[?(@.sku == 'CAM-003')]").isEmpty())
                .andExpect(jsonPath("$[?(@.sku == 'ZAP-001')]").isEmpty());
        filter("name", "zzz", "maxStock", "1000").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("name", "camiseta", "maxStock", "0").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("minPrice", "1000", "maxStock", "1000").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- inclusive stock limits -----------------------------------------------------------------

    @Test
    @WithMockUser
    void stockExactlyEqualToMaxStockIsIncludedAndJustAboveIsExcluded() throws Exception {
        filter("maxStock", "10").andExpect(jsonPath("$[?(@.sku == 'SUD-001')]").isNotEmpty());
        filter("maxStock", "9").andExpect(jsonPath("$[?(@.sku == 'SUD-001')]").isEmpty());
        filter("maxStock", "9").andExpect(jsonPath("$[?(@.sku == 'CAM-004')]").isNotEmpty());
        filter("maxStock", "30").andExpect(jsonPath("$[?(@.sku == 'CAM-002')]").isNotEmpty());
        filter("maxStock", "29").andExpect(jsonPath("$[?(@.sku == 'CAM-002')]").isEmpty());
    }

    @Test
    @WithMockUser
    void stockExactlyEqualToMinStockIsIncludedAndJustBelowIsExcluded() throws Exception {
        filter("minStock", "10", "maxStock", "30").andExpect(jsonPath("$[?(@.sku == 'SUD-001')]").isNotEmpty());
        filter("minStock", "11", "maxStock", "30").andExpect(jsonPath("$[?(@.sku == 'SUD-001')]").isEmpty());
        filter("minStock", "10", "maxStock", "30").andExpect(jsonPath("$[?(@.sku == 'CAM-002')]").isNotEmpty());
        filter("minStock", "10", "maxStock", "29").andExpect(jsonPath("$[?(@.sku == 'CAM-002')]").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"10, 10, SUD-001", "12, 12, CAM-005", "0, 0, ZAP-001", "30, 30, CAM-002", "40, 40, PAN-001"})
    @WithMockUser
    void minStockEqualToMaxStockIsAValidRangeForAnExactStock(String min, String max, String expectedSku)
            throws Exception {
        filter("minStock", min, "maxStock", max)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains(expectedSku)));
    }

    @Test
    @WithMockUser
    void aRangeOfExactStockWithNoMatchingProductReturnsEmptyList() throws Exception {
        filter("minStock", "7", "maxStock", "7").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("minStock", "31", "maxStock", "39").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- maxStock = 0 ---------------------------------------------------------------------------

    @Test
    @WithMockUser
    void zeroMaxStockIsValidAndReturnsOnlyProductsWithZeroStock() throws Exception {
        filter("maxStock", "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("ZAP-001")));
        filter("minStock", "0", "maxStock", "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("ZAP-001")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "00", "+0", "-0", "0000"})
    @WithMockUser
    void equivalentRepresentationsOfZeroMaxStockAreValid(String maxStock) throws Exception {
        filter("maxStock", maxStock).andExpect(status().isOk()).andExpect(jsonPath("$[*].sku", contains("ZAP-001")));
    }

    // --- 400: maxStock present but invalid ------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-2", "-5", "-10", "-100", "-2147483648"})
    @WithMockUser
    void negativeMaxStockIsRejected(String maxStock) throws Exception {
        filter("maxStock", maxStock).andExpect(status().isBadRequest());
        filter("name", "camiseta", "minStock", "0", "maxStock", maxStock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "10abc", "abc10", "1,0", "NaN", "Infinity", "$10", "1_0", "true", "ten"})
    @WithMockUser
    void nonNumericMaxStockIsRejected(String maxStock) throws Exception {
        filter("maxStock", maxStock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.5", "0.5", "10.0", "10.00", "0.0", "1e1", "2.5E0", ".5", "10.", "-0.5"})
    @WithMockUser
    void decimalMaxStockIsRejected(String maxStock) throws Exception {
        filter("maxStock", maxStock).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "99999999999", "99999999999999999999", "-2147483649",
            "-99999999999999999999"})
    @WithMockUser
    void maxStockOutsideTheIntegerRangeIsRejected(String maxStock) throws Exception {
        filter("maxStock", maxStock).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void maximumIntegerMaxStockIsValid() throws Exception {
        filter("maxStock", "2147483647").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(8)));
    }

    // --- 400: minStock > maxStock ---------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"11, 10", "30, 0", "1, 0", "10, 9", "2147483647, 0", "2147483647, 2147483646", "100, 99"})
    @WithMockUser
    void minStockGreaterThanMaxStockIsRejectedWithAnExplanation(String min, String max) throws Exception {
        filter("minStock", min, "maxStock", max)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", containsString("minStock")))
                .andExpect(jsonPath("$.detail", containsString("maxStock")));
    }

    @Test
    @WithMockUser
    void invalidStockRangeIsRejectedEvenWithTheOtherFiltersValid() throws Exception {
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "30", "maxStock", "10")
                .andExpect(status().isBadRequest());
        filter("minStock", "30", "maxStock", "10").andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void stockRangeErrorMessageNamesTheOffendingValues() throws Exception {
        filter("minStock", "30", "maxStock", "10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("minStock (30) must be less than or equal to maxStock (10)"));
    }

    @Test
    @WithMockUser
    void aMinStockGreaterThanAMissingMaxStockIsNotARangeError() throws Exception {
        // The range rule only applies when both limits are present.
        filter("minStock", "9999").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void priceRangeAndStockRangeErrorsAreIndependent() throws Exception {
        filter("minPrice", "50", "maxPrice", "20", "minStock", "0", "maxStock", "10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("min (50)")));
        filter("minPrice", "20", "maxPrice", "50", "minStock", "30", "maxStock", "10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("minStock (30)")));
        filter("minPrice", "50", "maxPrice", "20", "minStock", "30", "maxStock", "10")
                .andExpect(status().isBadRequest());
    }

    // --- previous behaviour is preserved --------------------------------------------------------

    @Test
    @WithMockUser
    void withoutMaxStockTheBehaviourOfThePreviousVersionIsUnchanged() throws Exception {
        filter().andExpect(jsonPath("$", hasSize(8)));
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "10")
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "CAM-005")));
        filter("minPrice", "20", "maxPrice", "50")
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "PAN-001", "CAM-005")));
        filter("minStock", "10")
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "SUD-001", "PAN-001", "CAM-005")));
        filter("name", "camiseta", "maxPrice", "50", "minStock", "10")
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001", "CAM-005")));
    }

    @Test
    @WithMockUser
    void sameResultsAsTheDedicatedStockEndpointsForASingleStockLimit() throws Exception {
        assertThat(filter("minStock", "30").andReturn().getResponse().getContentAsString())
                .isEqualTo(mockMvc.perform(get("/api/products/by-min-stock").param("stock", "30"))
                        .andReturn().getResponse().getContentAsString());
        assertThat(filter("maxStock", "10").andReturn().getResponse().getContentAsString())
                .isEqualTo(mockMvc.perform(get("/api/products/by-max-stock").param("stock", "10"))
                        .andReturn().getResponse().getContentAsString());
        assertThat(filter("minStock", "10", "maxStock", "10").andReturn().getResponse().getContentAsString())
                .isEqualTo(mockMvc.perform(get("/api/products/by-stock").param("stock", "10"))
                        .andReturn().getResponse().getContentAsString());
    }

    @Test
    @WithMockUser
    void sameResultsAsTheCombinedNameEndpointsForASingleStockLimit() throws Exception {
        assertThat(filter("name", "camiseta", "maxStock", "10").andReturn().getResponse().getContentAsString())
                .isEqualTo(mockMvc.perform(get("/api/products/search-by-max-stock")
                                .param("name", "camiseta").param("maxStock", "10"))
                        .andReturn().getResponse().getContentAsString());
        assertThat(filter("name", "camiseta", "minStock", "10").andReturn().getResponse().getContentAsString())
                .isEqualTo(mockMvc.perform(get("/api/products/search-by-stock")
                                .param("name", "camiseta").param("minStock", "10"))
                        .andReturn().getResponse().getContentAsString());
    }

    // --- optional parameters --------------------------------------------------------------------

    @Test
    @WithMockUser
    void noFilterReturnsEveryProductOrderedByIdAscending() throws Exception {
        filter()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku",
                        contains("CAM-001", "CAM-002", "CAM-003", "SUD-001", "PAN-001", "CAM-004", "ZAP-001", "CAM-005")));
    }

    @Test
    @WithMockUser
    void eitherStockLimitAloneIsValid() throws Exception {
        filter("minStock", "30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "PAN-001")));
        filter("maxStock", "30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[?(@.sku == 'PAN-001')]").isEmpty());
    }

    /** Spring converts an empty value of a numeric parameter to null, i.e. "not provided". */
    @Test
    @WithMockUser
    void emptyMaxStockIsTreatedAsAbsent() throws Exception {
        mockMvc.perform(get(URL + "?maxStock=")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(8)));
        mockMvc.perform(get(URL + "?minStock=10&maxStock="))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "SUD-001", "PAN-001", "CAM-005")));
    }

    @Test
    @WithMockUser
    void unknownParametersAreIgnored() throws Exception {
        filter("min", "1", "max", "2", "stock", "3").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(8)));
        filter("maxStock", "10", "foo", "bar").andExpect(jsonPath("$[*].sku", contains("CAM-003", "SUD-001", "CAM-004", "ZAP-001")));
    }

    // --- the other validations still hold -------------------------------------------------------

    @Test
    @WithMockUser
    void otherInvalidParametersAreStillRejectedAlongsideAValidMaxStock() throws Exception {
        filter("maxStock", "10", "name", " ").andExpect(status().isBadRequest());
        filter("maxStock", "10", "minPrice", "0").andExpect(status().isBadRequest());
        filter("maxStock", "10", "maxPrice", "-1").andExpect(status().isBadRequest());
        filter("maxStock", "10", "minStock", "-1").andExpect(status().isBadRequest());
        filter("maxStock", "10", "minStock", "1.5").andExpect(status().isBadRequest());
        filter("maxStock", "10", "minPrice", "abc").andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void severalInvalidParametersAtOnceAreRejected() throws Exception {
        filter("name", " ", "minPrice", "0", "maxPrice", "-1", "minStock", "-1", "maxStock", "-1")
                .andExpect(status().isBadRequest());
        filter("maxStock", "abc", "minStock", "xyz").andExpect(status().isBadRequest());
    }

    // --- ordering / number of results -----------------------------------------------------------

    @Test
    @WithMockUser
    void resultsAreOrderedByIdAscendingRegardlessOfPriceStockOrName() throws Exception {
        save("Camiseta alfa", "CAM-A", "49.00", 29);
        save("Camiseta zeta", "CAM-Z", "21.00", 11);
        save("Camiseta beta", "CAM-B", "30.00", 20);

        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "10", "maxStock", "30")
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "CAM-005", "CAM-A", "CAM-Z", "CAM-B")));
        filter("minStock", "10", "maxStock", "30")
                .andExpect(jsonPath("$[*].sku",
                        contains("CAM-001", "CAM-002", "SUD-001", "CAM-005", "CAM-A", "CAM-Z", "CAM-B")));
    }

    @Test
    @WithMockUser
    void idsInTheResponseAreAscending() throws Exception {
        save("Camiseta alfa", "CAM-A", "49.00", 11);
        int first = productRepository.findBySku("SUD-001").orElseThrow().getId().intValue();
        int second = productRepository.findBySku("CAM-005").orElseThrow().getId().intValue();
        int third = productRepository.findBySku("CAM-A").orElseThrow().getId().intValue();
        assertThat(first).isLessThan(second);
        assertThat(second).isLessThan(third);

        // CAM-A has the lowest stock of the three but was inserted last, so ordering by id puts it last.
        filter("minStock", "10", "maxStock", "12", "minPrice", "20")
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[1].id").value(second))
                .andExpect(jsonPath("$[2].id").value(third));
    }

    @Test
    @WithMockUser
    void singleResultIsReturned() throws Exception {
        filter("name", "sudadera", "minStock", "10", "maxStock", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("SUD-001"));
    }

    @Test
    @WithMockUser
    void noMatchesReturnsOkWithEmptyList() throws Exception {
        filter("name", "sombrero", "maxStock", "1000").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("minStock", "13", "maxStock", "24").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("name", "zapatillas", "minStock", "1", "maxStock", "100")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void emptyCatalogReturnsOkWithEmptyList() throws Exception {
        productRepository.deleteAll();

        filter("maxStock", "10").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "0", "maxStock", "100")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    // --- the name rules still hold with maxStock ------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"  camiseta", "camiseta  ", "   camiseta   ", "\tcamiseta\t"})
    @WithMockUser
    void leadingAndTrailingWhitespaceInTheNameIsStillRemoved(String name) throws Exception {
        filter("name", name, "maxStock", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-003", "SUD-001", "CAM-004")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"camiseta", "CAMISETA", "CaMiSeTa", "Camiseta"})
    @WithMockUser
    void nameIsStillCaseInsensitive(String name) throws Exception {
        filter("name", name, "maxStock", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-003", "SUD-001", "CAM-004")));
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "   ", "\t", " \t \n ", " "})
    @WithMockUser
    void blankNameIsStillRejected(String name) throws Exception {
        filter("name", name, "maxStock", "10").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "%%", "c_miseta", "cam%eta", "camiseta%", "\\"})
    @WithMockUser
    void likeWildcardsAndBackslashInTheNameAreStillTakenLiterally(String name) throws Exception {
        filter("name", name, "maxStock", "1000").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void literalPercentAndUnderscoreInNamesCanStillBeFound() throws Exception {
        save("Algodón 100% Orgánico", "ALG-001", "29.00", 3);
        save("Cinta_Roja", "CIN-001", "22.00", 4);

        filter("name", "100%", "maxStock", "5").andExpect(jsonPath("$[*].sku", contains("ALG-001")));
        filter("name", "a_r", "maxStock", "5").andExpect(jsonPath("$[*].sku", contains("CIN-001")));
        filter("name", "100%", "maxStock", "2").andExpect(content().string("[]"));
    }

    // --- the price rules still hold with maxStock -----------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "-0.01", "-1", "abc"})
    @WithMockUser
    void invalidMinPriceAndMaxPriceAreStillRejectedWithMaxStock(String value) throws Exception {
        filter("minPrice", value, "maxStock", "10").andExpect(status().isBadRequest());
        filter("maxPrice", value, "maxStock", "10").andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void minPriceEqualToMaxPriceIsStillValidWithMaxStock() throws Exception {
        filter("minPrice", "50.00", "maxPrice", "50.00", "maxStock", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001")));
    }

    // --- read-only / routing --------------------------------------------------------------------

    @Test
    @WithMockUser
    void queryDoesNotModifyProductsOrTheirData() throws Exception {
        List<String> before = snapshot();

        filter().andExpect(status().isOk());
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "10", "maxStock", "30")
                .andExpect(status().isOk());
        filter("minStock", "30", "maxStock", "10").andExpect(status().isBadRequest());
        filter("maxStock", "-1").andExpect(status().isBadRequest());
        filter("maxStock", "abc").andExpect(status().isBadRequest());

        assertThat(snapshot()).isEqualTo(before);
        assertThat(productRepository.count()).isEqualTo(8);
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
    void onlyGetIsSupportedOnTheEndpointAndOtherMethodsDoNotChangeData() throws Exception {
        List<String> before = snapshot();

        mockMvc.perform(post(URL).param("maxStock", "10").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL).param("maxStock", "10").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(URL).param("maxStock", "10")).andExpect(status().isMethodNotAllowed());

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @WithMockUser
    void mandatoryFilterEndpointAndAllOtherExistingEndpointsStillWork() throws Exception {
        mockMvc.perform(get("/api/products/filter").param("name", "camiseta").param("maxPrice", "50")
                        .param("minStock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001", "CAM-005")));
        mockMvc.perform(get("/api/products/filter")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/products/search").param("name", "camiseta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)));
        mockMvc.perform(get("/api/products/search-by-stock").param("name", "camiseta").param("minStock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "SUD-001", "CAM-005")));
        mockMvc.perform(get("/api/products/search-by-price").param("name", "camiseta").param("maxPrice", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-003", "SUD-001", "CAM-005")));
        mockMvc.perform(get("/api/products/search-by-exact-stock").param("name", "camiseta").param("stock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001")));
        mockMvc.perform(get("/api/products/search-by-price-range").param("name", "camiseta")
                        .param("minPrice", "20").param("maxPrice", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "CAM-005")));
        mockMvc.perform(get("/api/products/by-max-price").param("price", "20.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-003", "CAM-005")));
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

    // --- security -------------------------------------------------------------------------------

    @Test
    void unauthenticatedRequestIsRejectedWithAndWithoutFilters() throws Exception {
        filter().andExpect(status().isUnauthorized()).andExpect(content().string(""));
        filter("maxStock", "10").andExpect(status().isUnauthorized()).andExpect(content().string(""));
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "10", "maxStock", "30")
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void unauthenticatedRequestWithInvalidParametersIsStillUnauthorizedNotBadRequest() throws Exception {
        filter("maxStock", "-1").andExpect(status().isUnauthorized());
        filter("maxStock", "abc").andExpect(status().isUnauthorized());
        filter("minStock", "30", "maxStock", "10").andExpect(status().isUnauthorized());
        filter("minPrice", "50", "maxPrice", "20").andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        mockMvc.perform(request("maxStock", "10").with(httpBasic("nexora_test", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(request("maxStock", "10").with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicWithCorrectCredentialsCanQuery() throws Exception {
        mockMvc.perform(request("maxStock", "10").with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));
        mockMvc.perform(request("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "10",
                        "maxStock", "30").with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }
}
