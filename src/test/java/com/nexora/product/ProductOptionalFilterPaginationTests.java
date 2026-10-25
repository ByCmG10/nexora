package com.nexora.product;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
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
 * NXR-024: pagination of /api/products/filter-optional. Runs against the isolated in-memory database from
 * src/test/resources/application.properties. Hibernate statistics are enabled for this class only, to prove that the
 * page is cut by the database (only the rows of the page are loaded) and not in memory.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
class ProductOptionalFilterPaginationTests {

    private static final String URL = "/api/products/filter-optional";
    private static final int TOTAL = 45;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /**
     * 45 products, inserted in order so that ids ascend with i: P-01..P-45.
     * price = i.00, stock = 2*i, name = "Camiseta i" when i is odd and "Pantalon i" when i is even.
     */
    @BeforeEach
    void seedProducts() {
        productRepository.deleteAll();
        for (int i = 1; i <= TOTAL; i++) {
            productRepository.save(new Product((i % 2 == 1 ? "Camiseta " : "Pantalon ") + i, sku(i),
                    new BigDecimal(i + ".00"), 2 * i, 1));
        }
    }

    private static String sku(int i) {
        return String.format("P-%02d", i);
    }

    private static String[] skus(int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(ProductOptionalFilterPaginationTests::sku).toArray(String[]::new);
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

    // --- defaults and the shape of the response -------------------------------------------------

    @Test
    @WithMockUser
    void withoutPageNorSizeTheFirstPageOfTwentyIsReturned() throws Exception {
        filter()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(20)))
                .andExpect(jsonPath("$.content[*].sku", contains((Object[]) skus(1, 20))))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20));
    }

    @Test
    @WithMockUser
    void defaultsAreTheSameAsExplicitPageZeroSizeTwenty() throws Exception {
        String implicit = filter().andReturn().getResponse().getContentAsString();
        String explicit = filter("page", "0", "size", "20").andReturn().getResponse().getContentAsString();

        assertThat(implicit).isEqualTo(explicit);
    }

    @Test
    @WithMockUser
    void explicitPageZeroSizeTwentyReturnsTheFirstPage() throws Exception {
        filter("page", "0", "size", "20")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains((Object[]) skus(1, 20))));
    }

    @Test
    @WithMockUser
    void responseCarriesTheContentAndTheFourPaginationMetadataFields() throws Exception {
        filter("page", "1", "size", "10")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page.size").value(10))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(TOTAL))
                .andExpect(jsonPath("$.page.totalPages").value(5));
    }

    @Test
    @WithMockUser
    void contentElementsKeepAllTheProductFields() throws Exception {
        filter("size", "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").isNumber())
                .andExpect(jsonPath("$.content[0].sku").value("P-01"))
                .andExpect(jsonPath("$.content[0].name").value("Camiseta 1"))
                .andExpect(jsonPath("$.content[0].price").value(1.00))
                .andExpect(jsonPath("$.content[0].stock").value(2))
                .andExpect(jsonPath("$.content[0].minimumStock").value(1));
    }

    // --- pages ----------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void pageOneReturnsTheSecondPage() throws Exception {
        filter("page", "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains((Object[]) skus(21, 40))))
                .andExpect(jsonPath("$.page.number").value(1));
    }

    @Test
    @WithMockUser
    void lastPartialPageContainsTheRemainingElements() throws Exception {
        filter("page", "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains((Object[]) skus(41, 45))))
                .andExpect(jsonPath("$.page.number").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(TOTAL))
                .andExpect(jsonPath("$.page.totalPages").value(3));
    }

    @Test
    @WithMockUser
    void customSizeIsHonouredOnEveryPage() throws Exception {
        filter("size", "10", "page", "0").andExpect(jsonPath("$.content[*].sku", contains((Object[]) skus(1, 10))));
        filter("size", "10", "page", "3").andExpect(jsonPath("$.content[*].sku", contains((Object[]) skus(31, 40))));
        filter("size", "10", "page", "4").andExpect(jsonPath("$.content[*].sku", contains((Object[]) skus(41, 45))));
    }

    @ParameterizedTest
    @CsvSource({"1, 45", "2, 23", "5, 9", "7, 7", "10, 5", "20, 3", "44, 2", "45, 1", "50, 1", "100, 1"})
    @WithMockUser
    void totalPagesIsTheCeilingOfTotalElementsOverSize(int size, int expectedPages) throws Exception {
        filter("size", String.valueOf(size))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(size))
                .andExpect(jsonPath("$.page.totalElements").value(TOTAL))
                .andExpect(jsonPath("$.page.totalPages").value(expectedPages));
    }

    @Test
    @WithMockUser
    void sizeOneIsValid() throws Exception {
        filter("size", "1", "page", "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].sku").value("P-01"))
                .andExpect(jsonPath("$.page.totalPages").value(TOTAL));
        filter("size", "1", "page", "44")
                .andExpect(jsonPath("$.content[*].sku", contains("P-45")));
    }

    @Test
    @WithMockUser
    void sizeOneHundredIsValidAndReturnsEverythingInOnePage() throws Exception {
        filter("size", "100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(TOTAL)))
                .andExpect(jsonPath("$.content[*].sku", contains((Object[]) skus(1, TOTAL))))
                .andExpect(jsonPath("$.page.size").value(100))
                .andExpect(jsonPath("$.page.totalPages").value(1));
    }

    // --- empty pages ----------------------------------------------------------------------------

    @Test
    @WithMockUser
    void aValidPageBeyondTheLastOneIsAnEmptyPageNotA404() throws Exception {
        filter("page", "3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.number").value(3))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(TOTAL))
                .andExpect(jsonPath("$.page.totalPages").value(3));
        filter("page", "1000", "size", "5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.totalElements").value(TOTAL));
    }

    @Test
    @WithMockUser
    void aPageWithNoMatchingProductsIsAnEmptyPage() throws Exception {
        filter("name", "sombrero")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
    }

    @Test
    @WithMockUser
    void emptyCatalogReturnsAnEmptyPage() throws Exception {
        productRepository.deleteAll();

        filter()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
        filter("page", "2", "size", "5", "name", "camiseta")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()));
    }

    @ParameterizedTest
    @CsvSource({"21474836, 100", "21474837, 100", "100000000, 100", "2147483647, 1", "2147483647, 100"})
    @WithMockUser
    void hugePageNumbersGiveAnEmptyPageEvenWhenTheOffsetOverflowsAnInt(int page, int size) throws Exception {
        filter("page", String.valueOf(page), "size", String.valueOf(size))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.number").value(page))
                .andExpect(jsonPath("$.page.size").value(size))
                .andExpect(jsonPath("$.page.totalElements").value(TOTAL));
    }

    // --- the filters keep working with pagination (AND) -----------------------------------------

    @Test
    @WithMockUser
    void nameFilterIsPaginated() throws Exception {
        // Odd i: 1, 3, ..., 45 -> 23 products.
        filter("name", "camiseta", "size", "10", "page", "0")
                .andExpect(jsonPath("$.content[*].sku",
                        contains("P-01", "P-03", "P-05", "P-07", "P-09", "P-11", "P-13", "P-15", "P-17", "P-19")))
                .andExpect(jsonPath("$.page.totalElements").value(23))
                .andExpect(jsonPath("$.page.totalPages").value(3));
        filter("name", "camiseta", "size", "10", "page", "2")
                .andExpect(jsonPath("$.content[*].sku", contains("P-41", "P-43", "P-45")))
                .andExpect(jsonPath("$.page.totalElements").value(23));
    }

    @Test
    @WithMockUser
    void priceAndStockFiltersArePaginated() throws Exception {
        // price 10..30 -> i in 10..30 (21 products); stock 2i in 30..50 -> i in 15..25 (11 products).
        filter("minPrice", "10", "maxPrice", "30", "size", "5")
                .andExpect(jsonPath("$.page.totalElements").value(21))
                .andExpect(jsonPath("$.page.totalPages").value(5))
                .andExpect(jsonPath("$.content[*].sku", contains((Object[]) skus(10, 14))));
        filter("minStock", "30", "maxStock", "50", "size", "5", "page", "2")
                .andExpect(jsonPath("$.page.totalElements").value(11))
                .andExpect(jsonPath("$.page.totalPages").value(3))
                .andExpect(jsonPath("$.content[*].sku", contains("P-25")));
    }

    @Test
    @WithMockUser
    void severalDynamicConditionsTogetherWithPagination() throws Exception {
        // camiseta (odd i) AND price 10..40 AND stock 20..70 (i in 10..35) -> odd i in 11..35: 13 products.
        filter("name", "camiseta", "minPrice", "10", "maxPrice", "40", "minStock", "20", "maxStock", "70", "size", "5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(13))
                .andExpect(jsonPath("$.page.totalPages").value(3))
                .andExpect(jsonPath("$.content[*].sku", contains("P-11", "P-13", "P-15", "P-17", "P-19")));
        filter("name", "camiseta", "minPrice", "10", "maxPrice", "40", "minStock", "20", "maxStock", "70", "size", "5",
                "page", "2")
                .andExpect(jsonPath("$.content[*].sku", contains("P-31", "P-33", "P-35")));
    }

    @Test
    @WithMockUser
    void filtersAreStillCombinedWithAnd() throws Exception {
        // Each filter alone matches more than the combination; the total reflects the intersection.
        filter("name", "camiseta").andExpect(jsonPath("$.page.totalElements").value(23));
        filter("minPrice", "10", "maxPrice", "30").andExpect(jsonPath("$.page.totalElements").value(21));
        filter("minStock", "30", "maxStock", "50").andExpect(jsonPath("$.page.totalElements").value(11));
        // odd i in 10..30 -> 11, 13, ..., 29 -> 10 products
        filter("name", "camiseta", "minPrice", "10", "maxPrice", "30")
                .andExpect(jsonPath("$.page.totalElements").value(10));
        filter("name", "camiseta", "minPrice", "10", "maxPrice", "30", "minStock", "30", "maxStock", "50")
                .andExpect(jsonPath("$.page.totalElements").value(6))
                .andExpect(jsonPath("$.content[*].sku", contains("P-15", "P-17", "P-19", "P-21", "P-23", "P-25")));
        // An impossible combination is empty although each filter alone matches.
        filter("name", "pantalon", "minPrice", "1", "maxPrice", "1")
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.content", empty()));
    }

    @Test
    @WithMockUser
    void aNameOnlyFilterWithAFullPageStillReportsTheCorrectTotal() throws Exception {
        // 22 even products with size 22 fill the page exactly: the total must still be right.
        filter("name", "pantalon", "size", "22")
                .andExpect(jsonPath("$.content", hasSize(22)))
                .andExpect(jsonPath("$.page.totalElements").value(22))
                .andExpect(jsonPath("$.page.totalPages").value(1));
        filter("name", "pantalon", "size", "21")
                .andExpect(jsonPath("$.content", hasSize(21)))
                .andExpect(jsonPath("$.page.totalElements").value(22))
                .andExpect(jsonPath("$.page.totalPages").value(2));
    }

    @Test
    @WithMockUser
    void equalityOfBothRangesIsStillValidWithPagination() throws Exception {
        filter("minPrice", "20", "maxPrice", "20", "size", "5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains("P-20")))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        filter("minStock", "40", "maxStock", "40", "page", "0", "size", "100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains("P-20")))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @WithMockUser
    void invalidPriceRangeKeepsTheExistingErrorWithPagination() throws Exception {
        filter("minPrice", "30", "maxPrice", "10", "page", "0", "size", "10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", containsString("min (30) must be less than or equal to max (10)")));
    }

    @Test
    @WithMockUser
    void invalidStockRangeKeepsTheExistingErrorWithPagination() throws Exception {
        filter("minStock", "30", "maxStock", "10", "page", "0", "size", "10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail")
                        .value("minStock (30) must be less than or equal to maxStock (10)"));
    }

    @Test
    @WithMockUser
    void otherExistingFilterValidationsStillApplyWithPagination() throws Exception {
        filter("name", " ", "page", "0", "size", "10").andExpect(status().isBadRequest());
        filter("minPrice", "0", "page", "0").andExpect(status().isBadRequest());
        filter("maxPrice", "abc", "size", "5").andExpect(status().isBadRequest());
        filter("minStock", "-1", "page", "1").andExpect(status().isBadRequest());
        filter("maxStock", "1.5", "size", "5").andExpect(status().isBadRequest());
    }

    // --- stable id ASC order across pages -------------------------------------------------------

    @Test
    @WithMockUser
    void pagesTogetherListEveryProductOnceInIdAscendingOrder() throws Exception {
        List<String> collected = new ArrayList<>();
        for (int page = 0; page < 7; page++) {
            String body = filter("size", "7", "page", String.valueOf(page)).andReturn().getResponse()
                    .getContentAsString();
            com.jayway.jsonpath.JsonPath.<List<String>>read(body, "$.content[*].sku").forEach(collected::add);
        }

        assertThat(collected).containsExactly(skus(1, TOTAL));
    }

    @Test
    @WithMockUser
    void idsAscendIdenticallyInsideEveryPage() throws Exception {
        long previous = 0;
        for (int page = 0; page < 5; page++) {
            String body = filter("size", "10", "page", String.valueOf(page)).andReturn().getResponse()
                    .getContentAsString();
            for (Number id : com.jayway.jsonpath.JsonPath.<List<Number>>read(body, "$.content[*].id")) {
                assertThat(id.longValue()).isGreaterThan(previous);
                previous = id.longValue();
            }
        }
    }

    @Test
    @WithMockUser
    void theSameRequestAlwaysReturnsTheSamePage() throws Exception {
        String first = filter("name", "camiseta", "size", "8", "page", "1").andReturn().getResponse()
                .getContentAsString();
        String second = filter("name", "camiseta", "size", "8", "page", "1").andReturn().getResponse()
                .getContentAsString();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @WithMockUser
    void orderIsByIdEvenWhenInsertionOrderDiffersFromPriceAndName() throws Exception {
        productRepository.deleteAll();
        productRepository.save(new Product("Zeta", "Z-1", new BigDecimal("1.00"), 1, 1));
        productRepository.save(new Product("Alfa", "A-2", new BigDecimal("999.00"), 99, 1));
        productRepository.save(new Product("Mu", "M-3", new BigDecimal("50.00"), 50, 1));

        filter("size", "2", "page", "0").andExpect(jsonPath("$.content[*].sku", contains("Z-1", "A-2")));
        filter("size", "2", "page", "1").andExpect(jsonPath("$.content[*].sku", contains("M-3")));
    }

    @Test
    @WithMockUser
    void aSortParameterDoesNotOverrideTheIdOrder() throws Exception {
        // Only page and size are accepted: there is a single ordering mechanism, id ascending.
        filter("sort", "price,desc", "size", "5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains((Object[]) skus(1, 5))));
        filter("sort", "name,asc", "size", "5")
                .andExpect(jsonPath("$.content[*].sku", contains((Object[]) skus(1, 5))));
    }

    // --- pagination is done by the database -----------------------------------------------------

    private Statistics statistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    @Test
    @WithMockUser
    void onlyTheRowsOfThePageAreLoadedFromTheDatabase() throws Exception {
        Statistics statistics = statistics();

        statistics.clear();
        filter("size", "5", "page", "3").andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(5)));
        assertThat(statistics.getEntityLoadCount()).isEqualTo(5);

        statistics.clear();
        filter("name", "camiseta", "size", "4", "page", "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(4)))
                .andExpect(jsonPath("$.page.totalElements").value(23));
        assertThat(statistics.getEntityLoadCount()).isEqualTo(4);
    }

    @Test
    @WithMockUser
    void aPageBeyondTheEndLoadsNoEntitiesAtAll() throws Exception {
        Statistics statistics = statistics();

        statistics.clear();
        filter("page", "50", "size", "10").andExpect(status().isOk()).andExpect(jsonPath("$.content", empty()));
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    @WithMockUser
    void theLargestPageSizeLoadsAtMostTheCatalogueAndASmallOneLoadsFarFewerRows() throws Exception {
        Statistics statistics = statistics();

        statistics.clear();
        filter("size", "1").andExpect(status().isOk());
        long loadedForSizeOne = statistics.getEntityLoadCount();

        statistics.clear();
        filter("size", "100").andExpect(status().isOk());
        long loadedForSizeHundred = statistics.getEntityLoadCount();

        assertThat(loadedForSizeOne).isEqualTo(1);
        assertThat(loadedForSizeHundred).isEqualTo(TOTAL);
    }

    // --- 400: page and size ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-2", "-10", "-100", "-2147483648"})
    @WithMockUser
    void negativePageIsRejected(String page) throws Exception {
        filter("page", page)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        filter("page", page, "size", "10", "name", "camiseta").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-5", "-100", "-2147483648"})
    @WithMockUser
    void sizeBelowOneIsRejected(String size) throws Exception {
        filter("size", size)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        filter("size", size, "page", "0", "name", "camiseta").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"101", "102", "200", "1000", "2147483647"})
    @WithMockUser
    void sizeAboveOneHundredIsRejected(String size) throws Exception {
        filter("size", size)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        filter("size", size, "page", "0").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "2", "50", "99", "100"})
    @WithMockUser
    void everySizeFromOneToOneHundredIsAccepted(String size) throws Exception {
        filter("size", size).andExpect(status().isOk()).andExpect(jsonPath("$.page.size").value(Integer.parseInt(size)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1,0", "1.5", "NaN", "1e1", "true", "ten", "$5", "5abc", "2147483648",
            "99999999999999999999", "-2147483649"})
    @WithMockUser
    void nonIntegerPageIsRejected(String page) throws Exception {
        filter("page", page).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1,0", "1.5", "NaN", "1e1", "true", "ten", "$5", "5abc", "2147483648",
            "99999999999999999999", "-2147483649"})
    @WithMockUser
    void nonIntegerSizeIsRejected(String size) throws Exception {
        filter("size", size).andExpect(status().isBadRequest());
    }

    /** With a default value, Spring treats an empty page/size as not provided. */
    @Test
    @WithMockUser
    void emptyPageAndSizeFallBackToTheDefaults() throws Exception {
        mockMvc.perform(get(URL + "?page=&size="))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20));
    }

    @Test
    @WithMockUser
    void oneInvalidPaginationParameterRejectsTheWholeRequestEvenWithValidFilters() throws Exception {
        filter("name", "camiseta", "page", "-1", "size", "10").andExpect(status().isBadRequest());
        filter("name", "camiseta", "page", "0", "size", "101").andExpect(status().isBadRequest());
        filter("minPrice", "1", "maxPrice", "50", "page", "0", "size", "0").andExpect(status().isBadRequest());
    }

    // --- read-only / routing / regression -------------------------------------------------------

    @Test
    @WithMockUser
    void queryDoesNotModifyProductsOrTheirData() throws Exception {
        List<String> before = snapshot();

        filter().andExpect(status().isOk());
        filter("page", "1", "size", "10", "name", "camiseta").andExpect(status().isOk());
        filter("page", "-1").andExpect(status().isBadRequest());
        filter("size", "101").andExpect(status().isBadRequest());
        filter("page", "99", "size", "1").andExpect(status().isOk());

        assertThat(snapshot()).isEqualTo(before);
        assertThat(productRepository.count()).isEqualTo(TOTAL);
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

        mockMvc.perform(post(URL).param("page", "0").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL).param("page", "0").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(URL).param("page", "0")).andExpect(status().isMethodNotAllowed());

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @WithMockUser
    void otherEndpointsAreNotPaginatedAndStillReturnPlainArrays() throws Exception {
        // Only /filter-optional changed its contract: every other endpoint still returns a JSON array.
        mockMvc.perform(get("/api/products/filter").param("name", "camiseta").param("maxPrice", "100")
                        .param("minStock", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(23)));
        mockMvc.perform(get("/api/products/search").param("name", "pantalon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(22)));
        mockMvc.perform(get("/api/products/by-min-stock").param("stock", "80"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains((Object[]) skus(40, 45))));
        mockMvc.perform(get("/api/products/by-max-stock").param("stock", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("P-01", "P-02", "P-03")));
        mockMvc.perform(get("/api/products/by-stock").param("stock", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("P-05")));
        mockMvc.perform(get("/api/products/by-max-price").param("price", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("P-01", "P-02", "P-03")));
        mockMvc.perform(get("/api/products/by-price-range").param("min", "4").param("max", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("P-04", "P-05")));
        mockMvc.perform(get("/api/products/search-by-price-range").param("name", "camiseta")
                        .param("minPrice", "1").param("maxPrice", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("P-01", "P-03", "P-05")));
        mockMvc.perform(get("/api/products/out-of-stock"))
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
        mockMvc.perform(get("/api/products/low-stock")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/products/sku/P-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Camiseta 7"));
        mockMvc.perform(get("/api/products/sku/NO-SUCH-SKU")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void theFilterContentEqualsTheMandatoryFilterEndpointWhenEverythingFitsInOnePage() throws Exception {
        String paged = filter("name", "camiseta", "maxPrice", "30", "minStock", "10", "size", "100")
                .andReturn().getResponse().getContentAsString();
        String array = mockMvc.perform(get("/api/products/filter").param("name", "camiseta")
                        .param("maxPrice", "30").param("minStock", "10"))
                .andReturn().getResponse().getContentAsString();

        assertThat(com.jayway.jsonpath.JsonPath.<Object>read(paged, "$.content"))
                .isEqualTo(com.jayway.jsonpath.JsonPath.<Object>read(array, "$"));
    }

    // --- security -------------------------------------------------------------------------------

    @Test
    void unauthenticatedRequestIsRejectedWithAndWithoutPagination() throws Exception {
        filter().andExpect(status().isUnauthorized()).andExpect(content().string(""));
        filter("page", "0", "size", "10").andExpect(status().isUnauthorized()).andExpect(content().string(""));
        filter("name", "camiseta", "page", "1", "size", "5").andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedRequestWithInvalidPaginationIsStillUnauthorizedNotBadRequest() throws Exception {
        filter("page", "-1").andExpect(status().isUnauthorized());
        filter("size", "0").andExpect(status().isUnauthorized());
        filter("size", "101").andExpect(status().isUnauthorized());
        filter("size", "abc").andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        mockMvc.perform(request("page", "0", "size", "10").with(httpBasic("nexora_test", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(request("page", "0").with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicWithCorrectCredentialsCanQueryAPage() throws Exception {
        mockMvc.perform(request("page", "1", "size", "10").with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains((Object[]) skus(11, 20))))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(TOTAL));
    }
}
