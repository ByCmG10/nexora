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
 * NXR-022: the optional filter endpoint gains minPrice. Runs against the isolated in-memory database from
 * src/test/resources/application.properties.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductOptionalFilterMinPriceTests {

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

    // --- minPrice alone / with each other filter ------------------------------------------------

    @Test
    @WithMockUser
    void onlyMinPriceFiltersByPriceAndIgnoresNameAndStock() throws Exception {
        filter("minPrice", "20")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "SUD-001", "PAN-001", "CAM-004", "ZAP-001", "CAM-005")));
    }

    @Test
    @WithMockUser
    void returnedProductsIncludeAllTheirFields() throws Exception {
        filter("minPrice", "89.90")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("CAM-002"))
                .andExpect(jsonPath("$[0].name").value("Camiseta premium"))
                .andExpect(jsonPath("$[0].price").value(89.90))
                .andExpect(jsonPath("$[0].stock").value(30))
                .andExpect(jsonPath("$[0].minimumStock").value(2))
                .andExpect(jsonPath("$[0].id").isNumber());
    }

    @Test
    @WithMockUser
    void minPriceAndMaxPriceDefineAnInclusiveRange() throws Exception {
        filter("minPrice", "20", "maxPrice", "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "PAN-001", "CAM-005")))
                .andExpect(jsonPath("$[*].price", contains(50.00, 35.00, 20.00)));
    }

    @Test
    @WithMockUser
    void nameAndMinPriceAreCombinedWithAnd() throws Exception {
        filter("name", "camiseta", "minPrice", "20")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "SUD-001", "CAM-004", "CAM-005")));
    }

    @Test
    @WithMockUser
    void minPriceAndMinStockAreCombinedWithAnd() throws Exception {
        filter("minPrice", "20", "minStock", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "SUD-001", "PAN-001", "CAM-005")));
    }

    @Test
    @WithMockUser
    void nameMinPriceAndMaxPriceAreCombinedWithAnd() throws Exception {
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "CAM-005")));
    }

    @Test
    @WithMockUser
    void allFourFiltersAreCombinedWithAnd() throws Exception {
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "CAM-005")));
        // Raising minStock to 11 removes SUD-001 (stock 10): only CAM-005 (stock 12) is left.
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "11")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-005")));
    }

    @Test
    @WithMockUser
    void eachFilterNarrowsTheResultWhenAdded() throws Exception {
        filter().andExpect(jsonPath("$", hasSize(8)));
        filter("name", "camiseta").andExpect(jsonPath("$", hasSize(6)));
        filter("name", "camiseta", "minPrice", "20").andExpect(jsonPath("$", hasSize(4)));
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50").andExpect(jsonPath("$", hasSize(2)));
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "11")
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @WithMockUser
    void combinationsAreAndNotOr() throws Exception {
        // With OR, products failing one condition (e.g. Pantalon: price in range but name differs) would appear.
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50")
                .andExpect(jsonPath("$[?(@.sku == 'PAN-001')]").isEmpty())
                .andExpect(jsonPath("$[?(@.sku == 'CAM-001')]").isEmpty())
                .andExpect(jsonPath("$[?(@.sku == 'CAM-004')]").isEmpty());
        filter("name", "zzz", "minPrice", "1", "maxPrice", "1000")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
        filter("name", "camiseta", "minPrice", "20", "minStock", "1000")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    // --- NXR-021 behaviour is preserved when minPrice is absent ---------------------------------

    @Test
    @WithMockUser
    void withoutMinPriceTheBehaviourOfTheOptionalFilterIsUnchanged() throws Exception {
        filter().andExpect(jsonPath("$", hasSize(8)));
        filter("name", "camiseta", "maxPrice", "50", "minStock", "10")
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "SUD-001", "CAM-005")));
        filter("maxPrice", "50")
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-003", "SUD-001", "PAN-001", "CAM-005")));
        filter("minStock", "10")
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "SUD-001", "PAN-001", "CAM-005")));
        filter("name", "camiseta")
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "CAM-003", "SUD-001", "CAM-004", "CAM-005")));
    }

    @Test
    @WithMockUser
    void sameResultsAsTheMandatoryTripleFilterWhenMinPriceIsAbsent() throws Exception {
        String optional = filter("name", "camiseta", "maxPrice", "50", "minStock", "10")
                .andReturn().getResponse().getContentAsString();
        String mandatory = mockMvc.perform(get("/api/products/filter")
                        .param("name", "camiseta").param("maxPrice", "50").param("minStock", "10"))
                .andReturn().getResponse().getContentAsString();

        assertThat(optional).isEqualTo(mandatory);
    }

    @Test
    @WithMockUser
    void sameResultsAsThePriceRangeEndpointsWhenBothLimitsAreGiven() throws Exception {
        String optionalNoName = filter("minPrice", "20", "maxPrice", "50")
                .andReturn().getResponse().getContentAsString();
        String rangeNoName = mockMvc.perform(get("/api/products/by-price-range").param("min", "20").param("max", "50"))
                .andReturn().getResponse().getContentAsString();
        String optionalWithName = filter("name", "camiseta", "minPrice", "20", "maxPrice", "50")
                .andReturn().getResponse().getContentAsString();
        String rangeWithName = mockMvc.perform(get("/api/products/search-by-price-range")
                        .param("name", "camiseta").param("minPrice", "20").param("maxPrice", "50"))
                .andReturn().getResponse().getContentAsString();

        assertThat(optionalNoName).isEqualTo(rangeNoName);
        assertThat(optionalWithName).isEqualTo(rangeWithName);
    }

    // --- inclusive minPrice ---------------------------------------------------------------------

    @Test
    @WithMockUser
    void priceExactlyEqualToMinPriceIsIncluded() throws Exception {
        filter("minPrice", "20.00").andExpect(jsonPath("$[?(@.sku == 'CAM-005')]").isNotEmpty());
        filter("minPrice", "19.99").andExpect(jsonPath("$[?(@.sku == 'CAM-001')]").isNotEmpty());
        filter("minPrice", "89.90").andExpect(jsonPath("$[*].sku", contains("CAM-002")));
    }

    @Test
    @WithMockUser
    void priceJustBelowMinPriceIsExcluded() throws Exception {
        filter("minPrice", "20.01").andExpect(jsonPath("$[?(@.sku == 'CAM-005')]").isEmpty());
        filter("minPrice", "20.00").andExpect(jsonPath("$[?(@.sku == 'CAM-001')]").isEmpty());
        filter("minPrice", "89.91").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"20", "20.0", "20.00", "20.000", "2e1", "2E+1", "020.00", "+20.00"})
    @WithMockUser
    void equivalentRepresentationsOfTheSameMinPriceGiveTheSameResult(String minPrice) throws Exception {
        filter("minPrice", minPrice, "maxPrice", "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "PAN-001", "CAM-005")));
    }

    @Test
    @WithMockUser
    void minPriceWithMoreDecimalsThanTheColumnIsComparedExactly() throws Exception {
        filter("minPrice", "19.995").andExpect(jsonPath("$[?(@.sku == 'CAM-005')]").isNotEmpty());
        filter("minPrice", "19.995").andExpect(jsonPath("$[?(@.sku == 'CAM-001')]").isEmpty());
        filter("minPrice", "20.001").andExpect(jsonPath("$[?(@.sku == 'CAM-005')]").isEmpty());
    }

    @Test
    @WithMockUser
    void tinyAndVeryLargeMinPricesBehave() throws Exception {
        filter("minPrice", "0.0000001").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(8)));
        filter("minPrice", "99999999999999999999999.99").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- minPrice == maxPrice / only one price limit --------------------------------------------

    @ParameterizedTest
    @CsvSource({"20.00, 20.00, CAM-005", "50.00, 50.00, SUD-001", "19.99, 19.99, CAM-001", "89.90, 89.90, CAM-002"})
    @WithMockUser
    void minPriceEqualToMaxPriceIsAValidRangeForAnExactPrice(String min, String max, String expectedSku)
            throws Exception {
        filter("minPrice", min, "maxPrice", max)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains(expectedSku)));
    }

    @Test
    @WithMockUser
    void equalLimitsWithDifferentScalesAreAValidRange() throws Exception {
        filter("minPrice", "50", "maxPrice", "50.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001")));
        filter("minPrice", "5e1", "maxPrice", "50.0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001")));
        filter("minPrice", "40.00", "maxPrice", "40.00").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void eitherPriceLimitAloneIsValid() throws Exception {
        filter("minPrice", "50.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "SUD-001", "CAM-004", "ZAP-001")));
        filter("maxPrice", "20.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-003", "CAM-005")));
    }

    @Test
    @WithMockUser
    void aMinPriceAboveEveryPriceReturnsEmptyListNotAnError() throws Exception {
        filter("minPrice", "1000").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("name", "camiseta", "minPrice", "100").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- 400: minPrice present but invalid ------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "0.00", "-0", "-0.00", "0e0"})
    @WithMockUser
    void zeroMinPriceIsRejected(String minPrice) throws Exception {
        filter("minPrice", minPrice).andExpect(status().isBadRequest());
        filter("name", "camiseta", "minPrice", minPrice, "maxPrice", "50", "minStock", "10")
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-0.01", "-20.00", "-9999999999.99", "-1e3"})
    @WithMockUser
    void negativeMinPriceIsRejected(String minPrice) throws Exception {
        filter("minPrice", minPrice).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "20,00", "NaN", "Infinity", "$20", "20abc", "abc20", "0x14", "1_000", "20.00.00",
            "1e", "true"})
    @WithMockUser
    void nonNumericMinPriceIsRejected(String minPrice) throws Exception {
        filter("minPrice", minPrice).andExpect(status().isBadRequest());
    }

    // --- 400: minPrice > maxPrice ---------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"50.01, 50.00", "50.00, 20.00", "100, 99.99", "0.02, 0.01", "50.001, 50.000", "5e1, 49.99",
            "9999999999.99, 0.01"})
    @WithMockUser
    void minPriceGreaterThanMaxPriceIsRejectedWithAnExplanation(String min, String max) throws Exception {
        filter("minPrice", min, "maxPrice", max)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", containsString("min")))
                .andExpect(jsonPath("$.detail", containsString("max")));
    }

    @Test
    @WithMockUser
    void invalidRangeIsRejectedEvenWithTheOtherFiltersValid() throws Exception {
        filter("name", "camiseta", "minPrice", "50", "maxPrice", "20", "minStock", "10")
                .andExpect(status().isBadRequest());
        filter("minPrice", "50", "maxPrice", "20").andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void aMinPriceGreaterThanAMissingMaxPriceIsNotARangeError() throws Exception {
        // The range rule only applies when both limits are present.
        filter("minPrice", "9999").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("maxPrice", "1").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void invalidRangeReportsTheSameErrorAsThePlainPriceRangeEndpoint() throws Exception {
        String optional = filter("minPrice", "50", "maxPrice", "20")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        String plain = mockMvc.perform(get("/api/products/by-price-range").param("min", "50").param("max", "20"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(optional).contains("min (50) must be less than or equal to max (20)");
        assertThat(plain).contains("min (50) must be less than or equal to max (20)");
    }

    // --- the other validations still hold -------------------------------------------------------

    @Test
    @WithMockUser
    void otherInvalidParametersAreStillRejectedAlongsideAValidMinPrice() throws Exception {
        filter("minPrice", "20", "name", " ").andExpect(status().isBadRequest());
        filter("minPrice", "20", "maxPrice", "0").andExpect(status().isBadRequest());
        filter("minPrice", "20", "maxPrice", "abc").andExpect(status().isBadRequest());
        filter("minPrice", "20", "minStock", "-1").andExpect(status().isBadRequest());
        filter("minPrice", "20", "minStock", "1.5").andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void severalInvalidParametersAtOnceAreRejected() throws Exception {
        filter("name", " ", "minPrice", "0", "maxPrice", "-1", "minStock", "-1").andExpect(status().isBadRequest());
        filter("minPrice", "abc", "maxPrice", "xyz").andExpect(status().isBadRequest());
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

    /** Spring converts an empty value of a numeric parameter to null, i.e. "not provided". */
    @Test
    @WithMockUser
    void emptyMinPriceIsTreatedAsAbsent() throws Exception {
        mockMvc.perform(get(URL + "?minPrice=")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(8)));
        mockMvc.perform(get(URL + "?name=camiseta&minPrice=&maxPrice=50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-003", "SUD-001", "CAM-005")));
    }

    @Test
    @WithMockUser
    void unknownParametersAreIgnored() throws Exception {
        filter("min", "20", "max", "50").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(8)));
        filter("minPrice", "20", "foo", "bar", "maxPrice", "50")
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "PAN-001", "CAM-005")));
    }

    // --- ordering / number of results -----------------------------------------------------------

    @Test
    @WithMockUser
    void resultsAreOrderedByIdAscendingRegardlessOfPriceStockOrName() throws Exception {
        save("Camiseta alfa", "CAM-A", "49.00", 100);
        save("Camiseta zeta", "CAM-Z", "21.00", 11);
        save("Camiseta beta", "CAM-B", "30.00", 50);

        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "10")
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "CAM-005", "CAM-A", "CAM-Z", "CAM-B")));
        filter("minPrice", "20", "maxPrice", "50")
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "PAN-001", "CAM-005", "CAM-A", "CAM-Z", "CAM-B")));
    }

    @Test
    @WithMockUser
    void idsInTheResponseAreAscending() throws Exception {
        int first = productRepository.findBySku("SUD-001").orElseThrow().getId().intValue();
        int second = productRepository.findBySku("PAN-001").orElseThrow().getId().intValue();
        int third = productRepository.findBySku("CAM-005").orElseThrow().getId().intValue();
        assertThat(first).isLessThan(second);
        assertThat(second).isLessThan(third);

        // CAM-005 is the cheapest of the three but was inserted last, so ordering by id puts it last.
        filter("minPrice", "20", "maxPrice", "50")
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[1].id").value(second))
                .andExpect(jsonPath("$[2].id").value(third));
    }

    @Test
    @WithMockUser
    void singleResultIsReturned() throws Exception {
        filter("name", "sudadera", "minPrice", "20", "maxPrice", "50", "minStock", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("SUD-001"));
    }

    @Test
    @WithMockUser
    void noMatchesReturnsOkWithEmptyList() throws Exception {
        filter("name", "sombrero", "minPrice", "1").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("minPrice", "20.01", "maxPrice", "34.99").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("name", "zapatillas", "minPrice", "20", "maxPrice", "50")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void emptyCatalogReturnsOkWithEmptyList() throws Exception {
        productRepository.deleteAll();

        filter("minPrice", "20").andExpect(status().isOk()).andExpect(content().string("[]"));
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "0")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    // --- the name rules still hold with minPrice ------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"  camiseta", "camiseta  ", "   camiseta   ", "\tcamiseta\t"})
    @WithMockUser
    void leadingAndTrailingWhitespaceInTheNameIsStillRemoved(String name) throws Exception {
        filter("name", name, "minPrice", "20", "maxPrice", "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "CAM-005")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"camiseta", "CAMISETA", "CaMiSeTa", "Camiseta"})
    @WithMockUser
    void nameIsStillCaseInsensitive(String name) throws Exception {
        filter("name", name, "minPrice", "20", "maxPrice", "50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SUD-001", "CAM-005")));
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "   ", "\t", " \t \n ", " "})
    @WithMockUser
    void blankNameIsStillRejected(String name) throws Exception {
        filter("name", name, "minPrice", "20").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "%%", "c_miseta", "cam%eta", "camiseta%", "\\"})
    @WithMockUser
    void likeWildcardsAndBackslashInTheNameAreStillTakenLiterally(String name) throws Exception {
        filter("name", name, "minPrice", "0.01").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void literalPercentAndUnderscoreInNamesCanStillBeFound() throws Exception {
        save("Algodón 100% Orgánico", "ALG-001", "29.00", 1);
        save("Cinta_Roja", "CIN-001", "22.00", 1);

        filter("name", "100%", "minPrice", "25").andExpect(jsonPath("$[*].sku", contains("ALG-001")));
        filter("name", "a_r", "minPrice", "21").andExpect(jsonPath("$[*].sku", contains("CIN-001")));
        filter("name", "100%", "minPrice", "30").andExpect(content().string("[]"));
    }

    // --- read-only / routing --------------------------------------------------------------------

    @Test
    @WithMockUser
    void queryDoesNotModifyProductsOrTheirData() throws Exception {
        List<String> before = snapshot();

        filter().andExpect(status().isOk());
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "10").andExpect(status().isOk());
        filter("minPrice", "50", "maxPrice", "20").andExpect(status().isBadRequest());
        filter("minPrice", "0").andExpect(status().isBadRequest());
        filter("minPrice", "abc").andExpect(status().isBadRequest());

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

        mockMvc.perform(post(URL).param("minPrice", "20").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL).param("minPrice", "20").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(URL).param("minPrice", "20")).andExpect(status().isMethodNotAllowed());

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
        mockMvc.perform(get("/api/products/search-by-max-stock").param("name", "camiseta").param("maxStock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-003", "SUD-001", "CAM-004")));
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
        filter("minPrice", "20").andExpect(status().isUnauthorized()).andExpect(content().string(""));
        filter("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "10")
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void unauthenticatedRequestWithInvalidParametersIsStillUnauthorizedNotBadRequest() throws Exception {
        filter("minPrice", "0").andExpect(status().isUnauthorized());
        filter("minPrice", "-1").andExpect(status().isUnauthorized());
        filter("minPrice", "abc").andExpect(status().isUnauthorized());
        filter("minPrice", "50", "maxPrice", "20").andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        mockMvc.perform(request("minPrice", "20").with(httpBasic("nexora_test", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(request("minPrice", "20").with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicWithCorrectCredentialsCanQuery() throws Exception {
        mockMvc.perform(request("minPrice", "20", "maxPrice", "50")
                        .with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
        mockMvc.perform(request("name", "camiseta", "minPrice", "20", "maxPrice", "50", "minStock", "10")
                        .with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }
}
