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
 * NXR-026: partial, case-insensitive search by SKU at GET /api/products/search-by-sku?sku=... . Runs against the
 * isolated in-memory database from src/test/resources/application.properties.
 *
 * <pre>
 *  id  sku            name
 *   1  CAM-001        Camiseta basica
 *   2  CAM-002        Camiseta premium
 *   3  PAN-001        Pantalon cargo
 *   4  ZAP-100        Zapatillas running
 *   5  SUD-001-XL     Sudadera XL
 *   6  AB_1           Producto con guion bajo
 *   7  ABX1           Producto parecido
 *   8  xyz-lower-9    Producto con SKU en minusculas (insertado directamente, sin normalizar)
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductSkuSearchTests {

    private static final String URL = "/api/products/search-by-sku";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void seedProducts() {
        productRepository.deleteAll();
        save("Camiseta basica", "CAM-001", "19.99", 25);
        save("Camiseta premium", "CAM-002", "89.90", 30);
        save("Pantalon cargo", "PAN-001", "35.00", 40);
        save("Zapatillas running", "ZAP-100", "70.00", 0);
        save("Sudadera XL", "SUD-001-XL", "50.00", 10);
        save("Producto con guion bajo", "AB_1", "1.00", 1);
        save("Producto parecido", "ABX1", "2.00", 2);
        // Stored as is (the API would have upper-cased it) to prove the comparison itself ignores case.
        save("Producto con SKU en minusculas", "xyz-lower-9", "3.00", 3);
    }

    private void save(String name, String sku, String price, int stock) {
        productRepository.save(new Product(name, sku, new BigDecimal(price), stock, 2));
    }

    private ResultActions search(String sku) throws Exception {
        return mockMvc.perform(get(URL).param("sku", sku));
    }

    // --- partial matching: beginning, middle, end -----------------------------------------------

    @Test
    @WithMockUser
    void matchAtTheBeginningOfTheSku() throws Exception {
        search("CAM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002")));
        search("SUD-").andExpect(jsonPath("$[*].sku", contains("SUD-001-XL")));
        search("ZAP-100").andExpect(jsonPath("$[*].sku", contains("ZAP-100")));
    }

    @Test
    @WithMockUser
    void matchInTheMiddleOfTheSku() throws Exception {
        search("AN-0").andExpect(jsonPath("$[*].sku", contains("PAN-001")));
        search("AP-1").andExpect(jsonPath("$[*].sku", contains("ZAP-100")));
        search("001-X").andExpect(jsonPath("$[*].sku", contains("SUD-001-XL")));
        search("-00").andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002", "PAN-001", "SUD-001-XL")));
    }

    @Test
    @WithMockUser
    void matchAtTheEndOfTheSku() throws Exception {
        search("-XL").andExpect(jsonPath("$[*].sku", contains("SUD-001-XL")));
        search("100").andExpect(jsonPath("$[*].sku", contains("ZAP-100")));
        search("002").andExpect(jsonPath("$[*].sku", contains("CAM-002")));
        search("LOWER-9").andExpect(jsonPath("$[*].sku", contains("xyz-lower-9")));
    }

    @Test
    @WithMockUser
    void aSingleCharacterIsEnough() throws Exception {
        search("X").andExpect(jsonPath("$[*].sku", contains("SUD-001-XL", "ABX1", "xyz-lower-9")));
        search("9").andExpect(jsonPath("$[*].sku", contains("xyz-lower-9")));
    }

    @Test
    @WithMockUser
    void theWholeSkuMatchesOnlyThatProductNotItsNeighbours() throws Exception {
        search("CAM-001").andExpect(jsonPath("$[*].sku", contains("CAM-001")));
        search("CAM-002").andExpect(jsonPath("$[*].sku", contains("CAM-002")));
    }

    @Test
    @WithMockUser
    void severalProductsMatchAndTheyAreOrderedByIdAscending() throws Exception {
        search("001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "PAN-001", "SUD-001-XL")));
        search("-")
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[*].sku",
                        contains("CAM-001", "CAM-002", "PAN-001", "ZAP-100", "SUD-001-XL", "xyz-lower-9")));
    }

    @Test
    @WithMockUser
    void idsInTheResponseAscend() throws Exception {
        int first = productRepository.findBySku("CAM-001").orElseThrow().getId().intValue();
        int second = productRepository.findBySku("PAN-001").orElseThrow().getId().intValue();
        int third = productRepository.findBySku("SUD-001-XL").orElseThrow().getId().intValue();
        assertThat(first).isLessThan(second);
        assertThat(second).isLessThan(third);

        search("001")
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[1].id").value(second))
                .andExpect(jsonPath("$[2].id").value(third));
    }

    @Test
    @WithMockUser
    void returnedProductsIncludeAllTheirFields() throws Exception {
        search("ZAP")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].sku").value("ZAP-100"))
                .andExpect(jsonPath("$[0].name").value("Zapatillas running"))
                .andExpect(jsonPath("$[0].price").value(70.00))
                .andExpect(jsonPath("$[0].stock").value(0))
                .andExpect(jsonPath("$[0].minimumStock").value(2));
    }

    // --- case-insensitive -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"cam", "CAM", "Cam", "cAm", "caM-00", "CAM-00"})
    @WithMockUser
    void searchIgnoresCaseOfTheSearchText(String text) throws Exception {
        search(text).andExpect(status().isOk()).andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lower", "LOWER", "Lower", "XYZ-LOWER-9", "xyz-lower-9", "Xyz-Lower-9"})
    @WithMockUser
    void searchAlsoIgnoresCaseOfTheStoredSku(String text) throws Exception {
        search(text).andExpect(status().isOk()).andExpect(jsonPath("$[*].sku", contains("xyz-lower-9")));
    }

    // --- no matches -----------------------------------------------------------------------------

    @Test
    @WithMockUser
    void noMatchReturnsOkWithAnEmptyCollection() throws Exception {
        search("NOPE").andExpect(status().isOk()).andExpect(content().string("[]"));
        search("CAM-003").andExpect(status().isOk()).andExpect(content().string("[]"));
        search("0012").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void aSearchLongerThanAnySkuMatchesNothing() throws Exception {
        search("CAM-001-AND-SOMETHING-ELSE").andExpect(status().isOk()).andExpect(content().string("[]"));
        search("x".repeat(500)).andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void emptyCatalogueReturnsOkWithAnEmptyCollection() throws Exception {
        productRepository.deleteAll();

        search("CAM").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    // --- text handling: trimming and literal characters -----------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"  cam", "cam  ", "   cam   ", "\tcam\t", " CAM "})
    @WithMockUser
    void leadingAndTrailingWhitespaceOfTheSearchTextIsRemoved(String text) throws Exception {
        search(text).andExpect(status().isOk()).andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002")));
    }

    @Test
    @WithMockUser
    void internalWhitespaceIsKeptSoItMatchesNothing() throws Exception {
        // SKUs never contain spaces, and the text is not otherwise rewritten.
        search("CAM 001").andExpect(status().isOk()).andExpect(content().string("[]"));
        search("  CAM - 001  ").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void underscoreIsALiteralCharacterNotAWildcard() throws Exception {
        // "_" is valid in a SKU; it must not behave like the SQL "any single character".
        search("_").andExpect(jsonPath("$[*].sku", contains("AB_1")));
        search("B_1").andExpect(jsonPath("$[*].sku", contains("AB_1")));
        search("AB_").andExpect(jsonPath("$[*].sku", contains("AB_1")));
        search("A_1").andExpect(content().string("[]"));
        search("AB_").andExpect(jsonPath("$[?(@.sku == 'ABX1')]").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "%%", "C%M", "CAM%", "%001", "_%", "\\", "\\%", "[A-Z]", "C.M", "*", ".*"})
    @WithMockUser
    void wildcardsAndRegexCharactersAreTakenLiterallyAndMatchNothing(String text) throws Exception {
        search(text).andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void aLiteralPercentOrBackslashInAStoredSkuCanStillBeFound() throws Exception {
        save("Raro", "RARO%1", "1.00", 1);
        save("Raro2", "RARO\\2", "1.00", 1);

        search("O%1").andExpect(jsonPath("$[*].sku", contains("RARO%1")));
        search("%").andExpect(jsonPath("$[*].sku", contains("RARO%1")));
        search("O\\2").andExpect(jsonPath("$[*].sku", contains("RARO\\2")));
    }

    @Test
    @WithMockUser
    void textCarryingSqlLooksLikeAnyOtherTextAndMatchesNothing() throws Exception {
        search("' OR '1'='1").andExpect(status().isOk()).andExpect(content().string("[]"));
        search("CAM'; DROP TABLE products; --").andExpect(status().isOk()).andExpect(content().string("[]"));
        assertThat(productRepository.count()).isEqualTo(8);
    }

    // --- invalid input (same convention as /search?name=) ---------------------------------------

    @Test
    @WithMockUser
    void missingSkuParameterIsRejected() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("name", "CAM")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("text", "CAM")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void emptySkuIsRejected() throws Exception {
        search("").andExpect(status().isBadRequest());
        mockMvc.perform(get(URL + "?sku=")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "   ", "\t", " \t \n ", " "})
    @WithMockUser
    void whitespaceOnlySkuIsRejected(String text) throws Exception {
        search(text).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void invalidInputUsesTheSameErrorContractAsTheNameSearch() throws Exception {
        String skuError = search(" ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andReturn().getResponse().getContentType();
        String nameError = mockMvc.perform(get("/api/products/search").param("name", " "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andReturn().getResponse().getContentType();

        assertThat(skuError).isEqualTo(nameError);
    }

    // --- the exact lookup keeps working exactly as before ---------------------------------------

    @Test
    @WithMockUser
    void exactLookupByCompleteSkuIsUnchanged() throws Exception {
        mockMvc.perform(get("/api/products/sku/CAM-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("CAM-001"))
                .andExpect(jsonPath("$.name").value("Camiseta basica"))
                .andExpect(jsonPath("$.price").value(19.99))
                .andExpect(jsonPath("$.stock").value(25))
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    @WithMockUser
    void exactLookupStillNormalisesTheSkuAsBefore() throws Exception {
        mockMvc.perform(get("/api/products/sku/cam-001")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("CAM-001"));
        mockMvc.perform(get("/api/products/sku/{sku}", "  Cam-001  ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("CAM-001"));
    }

    @Test
    @WithMockUser
    void exactLookupIsStillNotPartial() throws Exception {
        // A partial text finds products through /search-by-sku but is a 404 for the exact endpoint.
        mockMvc.perform(get("/api/products/sku/CAM")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/products/sku/001")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/products/sku/CAM-00")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/products/sku/NO-SUCH-SKU")).andExpect(status().isNotFound());
        search("CAM").andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    @WithMockUser
    void exactLookupStillReturnsAnObjectNotAnArray() throws Exception {
        mockMvc.perform(get("/api/products/sku/PAN-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap());
        search("PAN-001").andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser
    void theNewRouteDoesNotCollideWithTheExactOneEvenForASkuNamedLikeTheRoute() throws Exception {
        save("Ruta", "SEARCH-BY-SKU", "1.00", 1);

        search("search-by")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("SEARCH-BY-SKU")));
        mockMvc.perform(get("/api/products/sku/SEARCH-BY-SKU"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ruta"));
    }

    @Test
    @WithMockUser
    void otherOperationsOnTheExactSkuRouteAreUnaffected() throws Exception {
        mockMvc.perform(put("/api/products/sku/CAM-001/stock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stock\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(7));
        mockMvc.perform(put("/api/products/sku/CAM-001/price").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":12.34}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(12.34));
        // The partial search sees the update; deleting through the exact route removes it from the search.
        search("CAM-001").andExpect(jsonPath("$[0].stock").value(7)).andExpect(jsonPath("$[0].price").value(12.34));
        mockMvc.perform(delete("/api/products/sku/CAM-001")).andExpect(status().isNoContent());
        search("CAM-001").andExpect(content().string("[]"));
        search("CAM").andExpect(jsonPath("$[*].sku", contains("CAM-002")));
    }

    // --- read-only ------------------------------------------------------------------------------

    private List<String> snapshot() {
        return productRepository.findAll().stream()
                .map(p -> p.getId() + "|" + p.getName() + "|" + p.getSku() + "|" + p.getPrice().toPlainString()
                        + "|" + p.getStock() + "|" + p.getMinimumStock())
                .sorted()
                .toList();
    }

    @Test
    @WithMockUser
    void searchDoesNotModifyProductsOrTheirData() throws Exception {
        List<String> before = snapshot();

        search("CAM").andExpect(status().isOk());
        search("lower").andExpect(status().isOk());
        search("NOPE").andExpect(status().isOk());
        search(" ").andExpect(status().isBadRequest());
        mockMvc.perform(get(URL)).andExpect(status().isBadRequest());

        assertThat(snapshot()).isEqualTo(before);
        assertThat(productRepository.count()).isEqualTo(8);
    }

    @Test
    @WithMockUser
    void searchDoesNotNormaliseOrRewriteStoredSkus() throws Exception {
        search("lower").andExpect(status().isOk());

        // The lower-case SKU inserted directly is still lower-case: searching never touches data.
        assertThat(productRepository.findBySku("xyz-lower-9")).isPresent();
        assertThat(productRepository.findBySku("XYZ-LOWER-9")).isEmpty();
    }

    @Test
    @WithMockUser
    void onlyGetIsSupportedOnTheEndpointAndOtherMethodsDoNotChangeData() throws Exception {
        List<String> before = snapshot();

        mockMvc.perform(post(URL).param("sku", "CAM").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL).param("sku", "CAM").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(URL).param("sku", "CAM")).andExpect(status().isMethodNotAllowed());

        assertThat(snapshot()).isEqualTo(before);
    }

    // --- regression of related functionality ----------------------------------------------------

    @Test
    @WithMockUser
    void otherSearchesAndFiltersStillWork() throws Exception {
        mockMvc.perform(get("/api/products/search").param("name", "camiseta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002")));
        mockMvc.perform(get("/api/products/filter-optional").param("name", "camiseta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", contains("CAM-001", "CAM-002")))
                .andExpect(jsonPath("$.page.totalElements").value(2));
        mockMvc.perform(get("/api/products/filter").param("name", "camiseta").param("maxPrice", "50")
                        .param("minStock", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001")));
        mockMvc.perform(get("/api/products/search-by-price").param("name", "camiseta").param("maxPrice", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001")));
        mockMvc.perform(get("/api/products/search-by-stock").param("name", "camiseta").param("minStock", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002")));
        mockMvc.perform(get("/api/products/by-max-price").param("price", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("AB_1", "ABX1", "xyz-lower-9")));
        mockMvc.perform(get("/api/products/by-min-stock").param("stock", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-002", "PAN-001")));
        mockMvc.perform(get("/api/products/out-of-stock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("ZAP-100")));
        mockMvc.perform(get("/api/products/low-stock")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void productCreationStillNormalisesTheSkuAndTheNewSearchFindsIt() throws Exception {
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nuevo\",\"sku\":\"  nuevo-77  \",\"price\":9.99,\"stock\":3,"
                                + "\"minimumStock\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("NUEVO-77"));

        search("uevo-7").andExpect(jsonPath("$[*].sku", contains("NUEVO-77")));
        search("UEVO-7").andExpect(jsonPath("$[*].sku", contains("NUEVO-77")));
    }

    // --- security -------------------------------------------------------------------------------

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        search("CAM").andExpect(status().isUnauthorized()).andExpect(content().string(""));
    }

    @Test
    void unauthenticatedRequestWithInvalidInputIsStillUnauthorizedNotBadRequest() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        search(" ").andExpect(status().isUnauthorized());
        search("").andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        mockMvc.perform(get(URL).param("sku", "CAM").with(httpBasic("nexora_test", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).param("sku", "CAM").with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicWithCorrectCredentialsCanSearch() throws Exception {
        mockMvc.perform(get(URL).param("sku", "cam").with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", contains("CAM-001", "CAM-002")));
    }

    @ParameterizedTest
    @CsvSource({"CAM, 2", "001, 3", "-, 6", "X, 3", "NOPE, 0"})
    @WithMockUser
    void numberOfMatchesIsCorrect(String text, int expected) throws Exception {
        search(text).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(expected)));
    }
}
