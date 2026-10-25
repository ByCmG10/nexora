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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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
 * NXR-025: sorting of /api/products/filter-optional with sortBy (price|stock) and direction (asc|desc).
 * Runs against the isolated in-memory database from src/test/resources/application.properties. Hibernate statistics
 * are enabled for this class only, to prove that the database does the sorting and the paging.
 *
 * <pre>
 *  id  sku  name           price  stock
 *   1  S-A  Camiseta A     30.00      5
 *   2  S-B  Camiseta B     10.00     50
 *   3  S-C  Pantalon C     20.00     20
 *   4  S-D  Camiseta D     10.00     20     (ties: price with B, stock with C)
 *   5  S-E  Camiseta E     30.00      5     (ties with A on both)
 *   6  S-F  Zapatillas F    5.00      0
 *   7  S-G  Camiseta G     20.00    100     (tie: price with C)
 * </pre>
 *
 * Ties are always broken by id ascending, so every expected order below is fully determined.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
class ProductOptionalFilterSortingTests {

    private static final String URL = "/api/products/filter-optional";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void seedProducts() {
        productRepository.deleteAll();
        save("Camiseta A", "A", "30.00", 5);
        save("Camiseta B", "B", "10.00", 50);
        save("Pantalon C", "C", "20.00", 20);
        save("Camiseta D", "D", "10.00", 20);
        save("Camiseta E", "E", "30.00", 5);
        save("Zapatillas F", "F", "5.00", 0);
        save("Camiseta G", "G", "20.00", 100);
    }

    private void save(String name, String letter, String price, int stock) {
        productRepository.save(new Product(name, "S-" + letter, new BigDecimal(price), stock, 1));
    }

    /** "FBD" -> {"S-F", "S-B", "S-D"}. */
    private static Object[] skus(String letters) {
        return letters.chars().mapToObj(c -> "S-" + (char) c).toArray();
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

    // --- the four requested orders --------------------------------------------------------------

    @Test
    @WithMockUser
    void priceAscending() throws Exception {
        filter("sortBy", "price", "direction", "asc")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains(skus("FBDCGAE"))))
                .andExpect(jsonPath("$.content[*].price", contains(5.00, 10.00, 10.00, 20.00, 20.00, 30.00, 30.00)));
    }

    @Test
    @WithMockUser
    void priceDescending() throws Exception {
        filter("sortBy", "price", "direction", "desc")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains(skus("AECGBDF"))))
                .andExpect(jsonPath("$.content[*].price", contains(30.00, 30.00, 20.00, 20.00, 10.00, 10.00, 5.00)));
    }

    @Test
    @WithMockUser
    void stockAscending() throws Exception {
        filter("sortBy", "stock", "direction", "asc")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains(skus("FAECDBG"))))
                .andExpect(jsonPath("$.content[*].stock", contains(0, 5, 5, 20, 20, 50, 100)));
    }

    @Test
    @WithMockUser
    void stockDescending() throws Exception {
        filter("sortBy", "stock", "direction", "desc")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains(skus("GBCDAEF"))))
                .andExpect(jsonPath("$.content[*].stock", contains(100, 50, 20, 20, 5, 5, 0)));
    }

    @Test
    @WithMockUser
    void ascendingIsTheDefaultDirectionWhenOnlySortByIsGiven() throws Exception {
        filter("sortBy", "price").andExpect(jsonPath("$.content[*].sku", contains(skus("FBDCGAE"))));
        filter("sortBy", "stock").andExpect(jsonPath("$.content[*].sku", contains(skus("FAECDBG"))));
    }

    @ParameterizedTest
    @CsvSource({"PRICE, DESC", "Price, Desc", "pRiCe, dEsC", "price, DESC", "PRICE, desc"})
    @WithMockUser
    void fieldAndDirectionAreCaseInsensitive(String sortBy, String direction) throws Exception {
        filter("sortBy", sortBy, "direction", direction)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains(skus("AECGBDF"))));
    }

    @Test
    @WithMockUser
    void ordersAreCorrectOnTheWholeRowNotJustOnTheSortedField() throws Exception {
        filter("sortBy", "price", "direction", "desc", "size", "1")
                .andExpect(jsonPath("$.content[0].id").isNumber())
                .andExpect(jsonPath("$.content[0].sku").value("S-A"))
                .andExpect(jsonPath("$.content[0].name").value("Camiseta A"))
                .andExpect(jsonPath("$.content[0].price").value(30.00))
                .andExpect(jsonPath("$.content[0].stock").value(5))
                .andExpect(jsonPath("$.content[0].minimumStock").value(1));
    }

    // --- ties are broken by id ascending --------------------------------------------------------

    @Test
    @WithMockUser
    void equalPricesAreOrderedByIdAscendingInBothDirections() throws Exception {
        // B (id 2) and D (id 4) share price 10.00; C (3) and G (7) share 20.00; A (1) and E (5) share 30.00.
        filter("sortBy", "price", "direction", "asc")
                .andExpect(jsonPath("$.content[?(@.price == 10.00)].sku", contains("S-B", "S-D")))
                .andExpect(jsonPath("$.content[?(@.price == 20.00)].sku", contains("S-C", "S-G")))
                .andExpect(jsonPath("$.content[?(@.price == 30.00)].sku", contains("S-A", "S-E")));
        filter("sortBy", "price", "direction", "desc")
                .andExpect(jsonPath("$.content[?(@.price == 10.00)].sku", contains("S-B", "S-D")))
                .andExpect(jsonPath("$.content[?(@.price == 30.00)].sku", contains("S-A", "S-E")));
    }

    @Test
    @WithMockUser
    void equalStocksAreOrderedByIdAscendingInBothDirections() throws Exception {
        filter("sortBy", "stock", "direction", "asc")
                .andExpect(jsonPath("$.content[?(@.stock == 5)].sku", contains("S-A", "S-E")))
                .andExpect(jsonPath("$.content[?(@.stock == 20)].sku", contains("S-C", "S-D")));
        filter("sortBy", "stock", "direction", "desc")
                .andExpect(jsonPath("$.content[?(@.stock == 5)].sku", contains("S-A", "S-E")))
                .andExpect(jsonPath("$.content[?(@.stock == 20)].sku", contains("S-C", "S-D")));
    }

    @Test
    @WithMockUser
    void theSameSortedRequestAlwaysReturnsTheSameOrder() throws Exception {
        String first = filter("sortBy", "price", "direction", "asc", "size", "3", "page", "1").andReturn()
                .getResponse().getContentAsString();
        String second = filter("sortBy", "price", "direction", "asc", "size", "3", "page", "1").andReturn()
                .getResponse().getContentAsString();

        assertThat(first).isEqualTo(second);
    }

    // --- no sorting requested: exactly the previous behaviour -----------------------------------

    @Test
    @WithMockUser
    void withoutSortByTheOrderIsStillIdAscending() throws Exception {
        filter()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains(skus("ABCDEFG"))));
    }

    @Test
    @WithMockUser
    void withoutSortByEveryFilterCombinationKeepsIdAscendingOrder() throws Exception {
        filter("name", "camiseta").andExpect(jsonPath("$.content[*].sku", contains(skus("ABDEG"))));
        filter("minPrice", "10", "maxPrice", "20").andExpect(jsonPath("$.content[*].sku", contains(skus("BCDG"))));
        filter("minStock", "5", "maxStock", "50").andExpect(jsonPath("$.content[*].sku", contains(skus("ABCDE"))));
    }

    @Test
    @WithMockUser
    void aSortParameterOfTheSpringDataStyleIsStillIgnored() throws Exception {
        // NXR-024 behaviour is kept: only sortBy / direction sort; "sort" does nothing.
        filter("sort", "price,desc").andExpect(jsonPath("$.content[*].sku", contains(skus("ABCDEFG"))));
        filter("sort", "stock,asc", "size", "3").andExpect(jsonPath("$.content[*].sku", contains(skus("ABC"))));
    }

    @Test
    @WithMockUser
    void aSortParameterDoesNotInterfereWhenSortByIsGiven() throws Exception {
        filter("sort", "price,desc", "sortBy", "stock", "direction", "asc")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("FAECDBG"))));
    }

    // --- sorting + filters ----------------------------------------------------------------------

    @Test
    @WithMockUser
    void sortingWithTheNameFilter() throws Exception {
        // camiseta -> A, B, D, E, G
        filter("name", "camiseta", "sortBy", "price", "direction", "asc")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("BDGAE"))));
        filter("name", "camiseta", "sortBy", "stock", "direction", "desc")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("GBDAE"))));
    }

    @Test
    @WithMockUser
    void sortingWithThePriceRange() throws Exception {
        // price 10..20 -> B, C, D, G
        filter("minPrice", "10", "maxPrice", "20", "sortBy", "price", "direction", "desc")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("CGBD"))));
        filter("minPrice", "10", "maxPrice", "20", "sortBy", "stock", "direction", "asc")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("CDBG"))));
    }

    @Test
    @WithMockUser
    void sortingWithTheStockRange() throws Exception {
        // stock 5..50 -> A, B, C, D, E
        filter("minStock", "5", "maxStock", "50", "sortBy", "stock", "direction", "desc")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("BCDAE"))));
        filter("minStock", "5", "maxStock", "50", "sortBy", "price", "direction", "asc")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("BDCAE"))));
    }

    @Test
    @WithMockUser
    void sortingWithEveryFilterAtOnce() throws Exception {
        // camiseta (A,B,D,E,G) AND price 10..30 AND stock 5..50 -> A, B, D, E
        filter("name", "camiseta", "minPrice", "10", "maxPrice", "30", "minStock", "5", "maxStock", "50",
                "sortBy", "price", "direction", "desc")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains(skus("AEBD"))))
                .andExpect(jsonPath("$.page.totalElements").value(4));
        filter("name", "camiseta", "minPrice", "10", "maxPrice", "30", "minStock", "5", "maxStock", "50",
                "sortBy", "stock", "direction", "asc")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("AEDB"))));
    }

    @Test
    @WithMockUser
    void sortingNeverChangesWhichProductsMatchOnlyTheirOrder() throws Exception {
        String[] reference = {"name", "camiseta", "minPrice", "10", "maxStock", "50"};
        List<String> idOrder = skuList(filter(reference));
        for (String[] sort : new String[][] {{"price", "asc"}, {"price", "desc"}, {"stock", "asc"}, {"stock", "desc"}}) {
            String[] params = new String[reference.length + 4];
            System.arraycopy(reference, 0, params, 0, reference.length);
            params[reference.length] = "sortBy";
            params[reference.length + 1] = sort[0];
            params[reference.length + 2] = "direction";
            params[reference.length + 3] = sort[1];
            assertThat(skuList(filter(params))).containsExactlyInAnyOrderElementsOf(idOrder);
        }
    }

    private List<String> skuList(ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.content[*].sku");
    }

    // --- sorting + pagination -------------------------------------------------------------------

    @Test
    @WithMockUser
    void sortedPagesFollowTheRequestedOrder() throws Exception {
        filter("sortBy", "price", "direction", "asc", "size", "3", "page", "0")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("FBD"))))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(3))
                .andExpect(jsonPath("$.page.totalElements").value(7))
                .andExpect(jsonPath("$.page.totalPages").value(3));
        filter("sortBy", "price", "direction", "asc", "size", "3", "page", "1")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("CGA"))));
        filter("sortBy", "price", "direction", "asc", "size", "3", "page", "2")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("E"))));
    }

    @Test
    @WithMockUser
    void sortedDescendingPagesFollowTheRequestedOrder() throws Exception {
        filter("sortBy", "stock", "direction", "desc", "size", "2", "page", "0")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("GB"))))
                .andExpect(jsonPath("$.page.totalPages").value(4));
        filter("sortBy", "stock", "direction", "desc", "size", "2", "page", "1")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("CD"))));
        filter("sortBy", "stock", "direction", "desc", "size", "2", "page", "2")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("AE"))));
        filter("sortBy", "stock", "direction", "desc", "size", "2", "page", "3")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("F"))));
    }

    @Test
    @WithMockUser
    void pagesTogetherAreTheFullSortedListWithNoGapsOrRepeatsEvenWithTies() throws Exception {
        for (String[] sort : new String[][] {{"price", "asc"}, {"price", "desc"}, {"stock", "asc"}, {"stock", "desc"}}) {
            List<String> full = skuList(filter("sortBy", sort[0], "direction", sort[1], "size", "100"));
            for (int size : new int[] {1, 2, 3, 5}) {
                List<String> collected = new ArrayList<>();
                for (int page = 0; page < 8; page++) {
                    collected.addAll(skuList(filter("sortBy", sort[0], "direction", sort[1],
                            "size", String.valueOf(size), "page", String.valueOf(page))));
                }
                assertThat(collected).as("sortBy=%s direction=%s size=%d", sort[0], sort[1], size).isEqualTo(full);
            }
        }
    }

    @Test
    @WithMockUser
    void sortingDoesNotChangeTheTotals() throws Exception {
        filter("name", "camiseta", "size", "2")
                .andExpect(jsonPath("$.page.totalElements").value(5))
                .andExpect(jsonPath("$.page.totalPages").value(3));
        filter("name", "camiseta", "size", "2", "sortBy", "price", "direction", "desc")
                .andExpect(jsonPath("$.page.totalElements").value(5))
                .andExpect(jsonPath("$.page.totalPages").value(3));
    }

    @Test
    @WithMockUser
    void filtersSortingAndPaginationTogether() throws Exception {
        // camiseta AND stock <= 50 (A, B, D, E), sorted by stock desc: B(50), D(20), A(5), E(5)
        filter("name", "camiseta", "maxStock", "50", "sortBy", "stock", "direction", "desc", "size", "2", "page", "0")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("BD"))))
                .andExpect(jsonPath("$.page.totalElements").value(4));
        filter("name", "camiseta", "maxStock", "50", "sortBy", "stock", "direction", "desc", "size", "2", "page", "1")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("AE"))));
    }

    // --- empty pages keep working ---------------------------------------------------------------

    @Test
    @WithMockUser
    void aPageBeyondTheEndIsAnEmptyPageWhenSorted() throws Exception {
        filter("sortBy", "price", "direction", "desc", "page", "5", "size", "3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.number").value(5))
                .andExpect(jsonPath("$.page.size").value(3))
                .andExpect(jsonPath("$.page.totalElements").value(7))
                .andExpect(jsonPath("$.page.totalPages").value(3));
    }

    @Test
    @WithMockUser
    void aSortedRequestWithNoMatchingProductsIsAnEmptyPage() throws Exception {
        filter("name", "sombrero", "sortBy", "stock", "direction", "asc")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
    }

    @Test
    @WithMockUser
    void aSortedRequestOverAnEmptyCatalogueIsAnEmptyPage() throws Exception {
        productRepository.deleteAll();

        filter("sortBy", "price", "direction", "desc")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @ParameterizedTest
    @CsvSource({"21474837, 100", "2147483647, 100", "2147483647, 1"})
    @WithMockUser
    void hugePageNumbersStillGiveAnEmptyPageWhenSorted(int page, int size) throws Exception {
        filter("sortBy", "price", "direction", "asc", "page", String.valueOf(page), "size", String.valueOf(size))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.totalElements").value(7));
    }

    // --- 400: disallowed fields, invalid directions ---------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"id", "name", "sku", "minimumStock", "minimum_stock", "ID", "Name", "createdAt", "pricee",
            "prices", "stocks", "price,desc", "price,stock", "price asc", "price;drop table products", "price)", "",
            " ", " price", "price ", "\tprice", "%", "_", "*", "1", "price\n", "products.price", "price.value"})
    @WithMockUser
    void disallowedSortFieldsAreRejected(String sortBy) throws Exception {
        filter("sortBy", sortBy)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        filter("sortBy", sortBy, "direction", "asc").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"up", "down", "ascending", "descending", "asc,desc", "desc,asc", "ASCENDING", "a", "d",
            "1", "0", "-1", "", " ", " asc", "asc ", "asc\n", "random", "true", "asc;", "%", "ASC DESC"})
    @WithMockUser
    void invalidDirectionsAreRejected(String direction) throws Exception {
        filter("sortBy", "price", "direction", direction)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        filter("sortBy", "stock", "direction", direction).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"asc", "desc", "ASC", "DESC"})
    @WithMockUser
    void aDirectionWithoutSortByIsRejected(String direction) throws Exception {
        filter("direction", direction)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("direction requires sortBy"));
        filter("direction", direction, "name", "camiseta", "page", "0", "size", "5").andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void repeatedSortParametersAreRejected() throws Exception {
        mockMvc.perform(get(URL + "?sortBy=price&sortBy=stock")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?sortBy=price&direction=asc&direction=desc")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void emptySortParametersAreRejectedNotIgnored() throws Exception {
        mockMvc.perform(get(URL + "?sortBy=")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?sortBy=price&direction=")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void aRejectedSortNeverReturnsData() throws Exception {
        filter("sortBy", "name", "direction", "asc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    // --- the other validations keep working with sorting ----------------------------------------

    @Test
    @WithMockUser
    void invalidFiltersAreStillRejectedWhenTheSortIsValid() throws Exception {
        filter("sortBy", "price", "direction", "asc", "name", " ").andExpect(status().isBadRequest());
        filter("sortBy", "price", "minPrice", "0").andExpect(status().isBadRequest());
        filter("sortBy", "price", "maxPrice", "abc").andExpect(status().isBadRequest());
        filter("sortBy", "stock", "minStock", "-1").andExpect(status().isBadRequest());
        filter("sortBy", "stock", "maxStock", "1.5").andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void invalidPaginationIsStillRejectedWhenTheSortIsValid() throws Exception {
        filter("sortBy", "price", "page", "-1").andExpect(status().isBadRequest());
        filter("sortBy", "price", "size", "0").andExpect(status().isBadRequest());
        filter("sortBy", "price", "size", "101").andExpect(status().isBadRequest());
        filter("sortBy", "price", "page", "abc").andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void rangeErrorsKeepTheirExactMessagesWhenSorting() throws Exception {
        filter("sortBy", "price", "direction", "desc", "minPrice", "30", "maxPrice", "10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("min (30) must be less than or equal to max (10)"));
        filter("sortBy", "stock", "direction", "desc", "minStock", "30", "maxStock", "10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("minStock (30) must be less than or equal to maxStock (10)"));
    }

    @Test
    @WithMockUser
    void equalLimitsStayValidWhenSorting() throws Exception {
        filter("minPrice", "20", "maxPrice", "20", "sortBy", "stock", "direction", "desc")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains(skus("GC"))));
        filter("minStock", "20", "maxStock", "20", "sortBy", "price", "direction", "asc")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains(skus("DC"))));
    }

    // --- the database does the sorting and the paging -------------------------------------------

    private Statistics statistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    @Test
    @WithMockUser
    void aSortedPageLoadsOnlyItsOwnRows() throws Exception {
        for (int i = 0; i < 40; i++) {
            save("Extra " + i, "X" + i, String.valueOf(100 + i) + ".00", 1000 + i);
        }
        Statistics statistics = statistics();

        statistics.clear();
        filter("sortBy", "price", "direction", "desc", "size", "5", "page", "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(5)))
                .andExpect(jsonPath("$.page.totalElements").value(47));
        assertThat(statistics.getEntityLoadCount()).isEqualTo(5);

        statistics.clear();
        filter("sortBy", "stock", "direction", "asc", "size", "3")
                .andExpect(jsonPath("$.content[*].sku", contains(skus("FAE"))));
        assertThat(statistics.getEntityLoadCount()).isEqualTo(3);
    }

    // --- read-only / routing / regression -------------------------------------------------------

    private List<String> snapshot() {
        return productRepository.findAll().stream()
                .map(p -> p.getId() + "|" + p.getName() + "|" + p.getSku() + "|" + p.getPrice().toPlainString()
                        + "|" + p.getStock() + "|" + p.getMinimumStock())
                .sorted()
                .toList();
    }

    @Test
    @WithMockUser
    void sortingDoesNotModifyProductsOrTheirData() throws Exception {
        List<String> before = snapshot();

        filter("sortBy", "price", "direction", "desc").andExpect(status().isOk());
        filter("sortBy", "stock", "direction", "asc", "size", "2", "page", "1").andExpect(status().isOk());
        filter("sortBy", "name").andExpect(status().isBadRequest());
        filter("direction", "asc").andExpect(status().isBadRequest());

        assertThat(snapshot()).isEqualTo(before);
        assertThat(productRepository.count()).isEqualTo(7);
    }

    @Test
    @WithMockUser
    void onlyGetIsSupportedOnTheEndpointAndOtherMethodsDoNotChangeData() throws Exception {
        List<String> before = snapshot();

        mockMvc.perform(post(URL).param("sortBy", "price").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL).param("sortBy", "price").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(URL).param("sortBy", "price")).andExpect(status().isMethodNotAllowed());

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @WithMockUser
    void otherEndpointsAreUnaffectedAndStillReturnPlainArraysInTheirOwnOrder() throws Exception {
        mockMvc.perform(get("/api/products/filter").param("name", "camiseta").param("maxPrice", "100")
                        .param("minStock", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains(skus("ABDEG"))));
        mockMvc.perform(get("/api/products/search").param("name", "pantalon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("S-C")));
        mockMvc.perform(get("/api/products/by-max-price").param("price", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains(skus("BDF"))));
        mockMvc.perform(get("/api/products/by-price-range").param("min", "20").param("max", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains(skus("ACEG"))));
        mockMvc.perform(get("/api/products/by-min-stock").param("stock", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains(skus("BG"))));
        mockMvc.perform(get("/api/products/by-max-stock").param("stock", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains(skus("AEF"))));
        mockMvc.perform(get("/api/products/by-stock").param("stock", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains(skus("CD"))));
        mockMvc.perform(get("/api/products/out-of-stock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("S-F")));
        mockMvc.perform(get("/api/products/low-stock")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/products/sku/S-C"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pantalon C"));
        mockMvc.perform(get("/api/products/sku/NO-SUCH-SKU")).andExpect(status().isNotFound());
    }

    // --- security -------------------------------------------------------------------------------

    @Test
    void unauthenticatedRequestIsRejectedWithAndWithoutSorting() throws Exception {
        filter().andExpect(status().isUnauthorized()).andExpect(content().string(""));
        filter("sortBy", "price", "direction", "desc").andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        filter("name", "camiseta", "sortBy", "stock", "page", "1", "size", "2").andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedRequestWithInvalidSortIsStillUnauthorizedNotBadRequest() throws Exception {
        filter("sortBy", "name").andExpect(status().isUnauthorized());
        filter("sortBy", "price", "direction", "up").andExpect(status().isUnauthorized());
        filter("direction", "asc").andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        mockMvc.perform(request("sortBy", "price").with(httpBasic("nexora_test", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(request("sortBy", "price").with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicWithCorrectCredentialsCanQuerySortedPages() throws Exception {
        mockMvc.perform(request("sortBy", "stock", "direction", "desc", "size", "3")
                        .with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains(skus("GBC"))))
                .andExpect(jsonPath("$.page.totalElements").value(7));
    }
}
