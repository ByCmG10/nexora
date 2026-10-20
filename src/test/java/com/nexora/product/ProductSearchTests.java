package com.nexora.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs against the isolated in-memory database from src/test/resources/application.properties. */
@SpringBootTest
@AutoConfigureMockMvc
class ProductSearchTests {

    private static final String URL = "/api/products/search";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void seedProducts() {
        productRepository.deleteAll();
        save("Camiseta Negra Premium", "CAM-001");
        save("Camiseta Blanca", "CAM-002");
        save("Pantalón Negro", "PAN-001");
        save("Zapatillas Running", "ZAP-001");
    }

    private void save(String name, String sku) {
        productRepository.save(new Product(name, sku, new BigDecimal("19.99"), 10, 2));
    }

    private ResultActions search(String name) throws Exception {
        return mockMvc.perform(get(URL).param("name", name));
    }

    // --- matching -------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void partialMatchReturnsProductsWithAllTheirFields() throws Exception {
        search("camiseta")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", containsInAnyOrder("Camiseta Negra Premium", "Camiseta Blanca")))
                .andExpect(jsonPath("$[*].sku", containsInAnyOrder("CAM-001", "CAM-002")))
                .andExpect(jsonPath("$[*].price", containsInAnyOrder(19.99, 19.99)))
                .andExpect(jsonPath("$[*].stock", containsInAnyOrder(10, 10)))
                .andExpect(jsonPath("$[*].minimumStock", containsInAnyOrder(2, 2)))
                .andExpect(jsonPath("$[*].id").isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"camiseta", "CAMISETA", "CaMiSeTa", "Camiseta"})
    @WithMockUser
    void searchIsCaseInsensitive(String term) throws Exception {
        search(term)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", containsInAnyOrder("Camiseta Negra Premium", "Camiseta Blanca")));
    }

    @Test
    @WithMockUser
    void caseInsensitivityAlsoWorksWithAccentedCharacters() throws Exception {
        search("PANTALÓN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", containsInAnyOrder("Pantalón Negro")));
        search("pantalón")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @WithMockUser
    void multipleMatchesAreReturned() throws Exception {
        search("negr")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name",
                        containsInAnyOrder("Camiseta Negra Premium", "Pantalón Negro")));
    }

    @Test
    @WithMockUser
    void nonMatchingProductsAreExcluded() throws Exception {
        search("camiseta")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Pantalón Negro')]").isEmpty())
                .andExpect(jsonPath("$[?(@.name == 'Zapatillas Running')]").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"zapat", "illas run", "running", "as r", "Zapatillas Running"})
    @WithMockUser
    void textCanAppearAtTheStartMiddleOrEndOfTheName(String term) throws Exception {
        search(term)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("ZAP-001"));
    }

    @Test
    @WithMockUser
    void noMatchesReturnsOkWithEmptyList() throws Exception {
        search("sombrero")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void emptyCatalogReturnsOkWithEmptyList() throws Exception {
        productRepository.deleteAll();

        search("camiseta")
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void searchTermIsNotMatchedAgainstOtherFields() throws Exception {
        search("CAM-001").andExpect(status().isOk()).andExpect(content().string("[]"));
        search("19.99").andExpect(status().isOk()).andExpect(content().string("[]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "%%", "c_miseta", "cam%eta", "camiseta%"})
    @WithMockUser
    void likeWildcardsInTheSearchTermAreTakenLiterally(String term) throws Exception {
        search(term)
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    @WithMockUser
    void literalPercentAndUnderscoreInNamesCanBeFound() throws Exception {
        save("Algodón 100% Orgánico", "ALG-001");
        save("Cinta_Roja", "CIN-001");

        search("100%").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].sku").value("ALG-001"));
        search("a_r").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].sku").value("CIN-001"));
    }

    // --- trimming -------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"  camiseta", "camiseta  ", "   camiseta   ", "\tcamiseta\t"})
    @WithMockUser
    void leadingAndTrailingWhitespaceInTheTermIsRemoved(String term) throws Exception {
        search(term)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", containsInAnyOrder("CAM-001", "CAM-002")));
    }

    @Test
    @WithMockUser
    void internalWhitespaceIsKept() throws Exception {
        search("  negra premium  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value("CAM-001"));
    }

    // --- 400 ------------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void missingNameParameterIsRejected() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void nameParameterWithoutValueIsRejected() throws Exception {
        mockMvc.perform(get(URL + "?name")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void emptyNameIsRejected() throws Exception {
        search("").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "   ", "\t", " \t \n "})
    @WithMockUser
    void whitespaceOnlyNameIsRejected(String name) throws Exception {
        search(name).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void otherParametersWithoutNameAreRejected() throws Exception {
        mockMvc.perform(get(URL).param("q", "camiseta")).andExpect(status().isBadRequest());
    }

    // --- routing / read-only --------------------------------------------------------------------

    @Test
    @WithMockUser
    void searchPathIsNotTreatedAsASkuAndOtherGetsStillWork() throws Exception {
        mockMvc.perform(get("/api/products/sku/CAM-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Camiseta Negra Premium"));
        mockMvc.perform(get("/api/products/low-stock")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void searchDoesNotModifyProductsOrTheirData() throws Exception {
        List<String> before = snapshot();

        search("camiseta").andExpect(status().isOk());
        search("zzz").andExpect(status().isOk());
        search(" ").andExpect(status().isBadRequest());

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

    // --- security -------------------------------------------------------------------------------

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        search("camiseta")
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void unauthenticatedRequestWithoutNameIsStillUnauthorizedNotBadRequest() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        mockMvc.perform(get(URL).param("name", "camiseta").with(httpBasic("nexora_test", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).param("name", "camiseta").with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicWithCorrectCredentialsCanSearch() throws Exception {
        mockMvc.perform(get(URL).param("name", "camiseta").with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }
}
