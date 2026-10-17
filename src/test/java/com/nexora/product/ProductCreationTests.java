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
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs against the isolated in-memory database from src/test/resources/application.properties.
 * Tests are not @Transactional so that the real commit/constraint behaviour is exercised;
 * the table is emptied before each test instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductCreationTests {

    private static final String VALID_BODY = body("Test Product", "TEST-NXR003-001", "12.50", "5", "2");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void cleanDatabase() {
        productRepository.deleteAll();
    }

    private static String body(String name, String sku, String price, String stock, String minimumStock) {
        return """
                {"name":"%s","sku":"%s","price":%s,"stock":%s,"minimumStock":%s}
                """.formatted(name, sku, price, stock, minimumStock);
    }

    private org.springframework.test.web.servlet.ResultActions create(String json) throws Exception {
        return mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    // --- 201 / persistence / generated id -------------------------------------------------------

    @Test
    @WithMockUser
    void validProductIsCreatedAndPersisted() throws Exception {
        create(VALID_BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/products/sku/TEST-NXR003-001"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Test Product"))
                .andExpect(jsonPath("$.sku").value("TEST-NXR003-001"))
                .andExpect(jsonPath("$.price").value(12.50))
                .andExpect(jsonPath("$.stock").value(5))
                .andExpect(jsonPath("$.minimumStock").value(2));

        assertThat(productRepository.count()).isEqualTo(1);
        Product saved = productRepository.findBySku("TEST-NXR003-001").orElseThrow();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getName()).isEqualTo("Test Product");
        assertThat(saved.getPrice()).isEqualByComparingTo("12.50");
        assertThat(saved.getStock()).isEqualTo(5);
        assertThat(saved.getMinimumStock()).isEqualTo(2);
    }

    @Test
    @WithMockUser
    void clientSuppliedIdIsIgnored() throws Exception {
        String json = """
                {"id":999999,"name":"Test Product","sku":"TEST-NXR003-002","price":1.00,"stock":0,"minimumStock":0}
                """;

        create(json)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(999999)));
        assertThat(productRepository.findAll()).noneMatch(p -> p.getId() == 999999L);
    }

    // --- SKU normalisation / duplicates ---------------------------------------------------------

    @Test
    @WithMockUser
    void skuIsTrimmedAndUpperCasedBeforePersisting() throws Exception {
        create(body("Name  Keeps Case ", "  abc-1  ", "1.00", "0", "0"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("ABC-1"))
                .andExpect(header().string("Location", "http://localhost/api/products/sku/ABC-1"));

        Product saved = productRepository.findBySku("ABC-1").orElseThrow();
        assertThat(saved.getSku()).isEqualTo("ABC-1");
        assertThat(saved.getName()).isEqualTo("Name  Keeps Case "); // name is not normalised
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABC-1", "abc-1", "Abc-1", "  abc-1", "ABC-1  ", "  aBc-1  "})
    @WithMockUser
    void sameSkuWithDifferentCaseOrSpacesReturnsConflict(String duplicate) throws Exception {
        create(body("First", "ABC-1", "1.00", "0", "0")).andExpect(status().isCreated());

        create(body("Second", duplicate, "2.00", "0", "0")).andExpect(status().isConflict());

        assertThat(productRepository.count()).isEqualTo(1);
        assertThat(productRepository.findBySku("ABC-1").orElseThrow().getName()).isEqualTo("First");
    }

    @Test
    @WithMockUser
    void exactDuplicateSkuReturnsConflictAndDoesNotCreateSecondProduct() throws Exception {
        create(VALID_BODY).andExpect(status().isCreated());
        create(VALID_BODY).andExpect(status().isConflict());

        assertThat(productRepository.count()).isEqualTo(1);
    }

    @Test
    @WithMockUser
    void createdProductIsFoundBySkuRegardlessOfCase() throws Exception {
        create(body("P", "abc-1", "1.00", "0", "0")).andExpect(status().isCreated());

        mockMvc.perform(get("/api/products/sku/abc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("ABC-1"));
    }

    // --- Location -------------------------------------------------------------------------------

    @Test
    @WithMockUser
    void locationPointsToTheGetEndpointOfTheCreatedProduct() throws Exception {
        MvcResult result = create(body("Located", "  loc_1-x ", "1.00", "0", "0"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/products/sku/LOC_1-X"))
                .andReturn();

        URI location = URI.create(result.getResponse().getHeader("Location"));
        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("LOC_1-X"));
    }

    // --- SKU format -----------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"A", "0123", "ABC-123", "abc_123", "a-b_C-9", "-", "_", "__--"})
    @WithMockUser
    void validSkuFormatsAreAccepted(String sku) throws Exception {
        create(body("P", sku, "1.00", "0", "0")).andExpect(status().isCreated());

        assertThat(productRepository.existsBySku(sku.toUpperCase(Locale.ROOT))).isTrue();
    }

    @Test
    @WithMockUser
    void skuOfMaximumLengthIsAcceptedAndLongerIsRejected() throws Exception {
        create(body("P", "A".repeat(255), "1.00", "0", "0")).andExpect(status().isCreated());
        create(body("P", "B".repeat(256), "1.00", "0", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sku").exists());

        assertThat(productRepository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AB/C", "AB%C", "AB?C", "AB#C", "AB C", "AB.C", "AB+C", "AB:C", "AB;C", "AB&C",
            "AB=C", "AB@C", "AB*C", "AB'C", "AB,C", "AB\\\\C", "ÑANDU", "ABé"})
    @WithMockUser
    void skuWithDisallowedCharactersIsRejected(String sku) throws Exception {
        create(body("P", sku, "1.00", "0", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sku").exists());

        assertThat(productRepository.count()).isZero();
    }

    // --- price precision / scale (numeric(12,2)) ------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "0.1", "1", "1.5", "12.50", "1.50", "9999999999.99", "9999999999"})
    @WithMockUser
    void pricesCompatibleWithTheColumnAreAcceptedAndPersisted(String price) throws Exception {
        create(body("P", "PRICE-OK", price, "0", "0")).andExpect(status().isCreated());

        assertThat(productRepository.findBySku("PRICE-OK").orElseThrow().getPrice())
                .isEqualByComparingTo(price);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10000000000", "10000000000.00", "99999999999.99", "1.005", "0.011", "1.999"})
    @WithMockUser
    void pricesExceedingPrecisionOrScaleAreRejected(String price) throws Exception {
        create(body("P", "PRICE-BAD", price, "0", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").exists());

        assertThat(productRepository.count()).isZero();
    }

    // --- 400: invalid data ----------------------------------------------------------------------

    @Test
    @WithMockUser
    void invalidDataIsRejectedAndNotPersisted() throws Exception {
        String invalidBody = """
                {"name":" ","sku":"TEST-NXR003-003","price":0,"stock":-1}
                """;

        create(invalidBody)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.price").exists())
                .andExpect(jsonPath("$.errors.stock").exists())
                .andExpect(jsonPath("$.errors.minimumStock").exists());

        assertThat(productRepository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"   \"", "null"})
    @WithMockUser
    void blankOrNullSkuIsRejected(String sku) throws Exception {
        create("""
                {"name":"P","sku":%s,"price":1.00,"stock":0,"minimumStock":0}
                """.formatted(sku))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sku").exists());

        assertThat(productRepository.count()).isZero();
    }

    @Test
    @WithMockUser
    void missingSkuIsRejected() throws Exception {
        create("""
                {"name":"P","price":1.00,"stock":0,"minimumStock":0}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sku").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"name\":\"P\",\"sku\":\"X\"", "not json", "{\"name\":\"P\",\"sku\":\"X\",\"price\":\"abc\"}", ""})
    @WithMockUser
    void malformedJsonIsRejected(String json) throws Exception {
        create(json).andExpect(status().isBadRequest());

        assertThat(productRepository.count()).isZero();
    }

    // --- boundaries -----------------------------------------------------------------------------

    @Test
    @WithMockUser
    void minimumAllowedValuesAreAccepted() throws Exception {
        create(body("P", "BOUNDARY-MIN", "0.01", "0", "0"))
                .andExpect(status().isCreated());

        Product saved = productRepository.findBySku("BOUNDARY-MIN").orElseThrow();
        assertThat(saved.getPrice()).isEqualByComparingTo("0.01");
        assertThat(saved.getStock()).isZero();
        assertThat(saved.getMinimumStock()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
            "0.00, 0, 0, price",
            "0.009, 0, 0, price",
            "-1.00, 0, 0, price",
            "1.00, -1, 0, stock",
            "1.00, 0, -1, minimumStock"
    })
    @WithMockUser
    void valuesJustBelowTheLimitsAreRejected(String price, String stock, String minimumStock, String field)
            throws Exception {
        create(body("P", "BOUNDARY-BAD", price, stock, minimumStock))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + field).exists());

        assertThat(productRepository.count()).isZero();
    }

    // --- authentication / CSRF ------------------------------------------------------------------

    @Test
    void unauthenticatedRequestIsRejectedAndNotPersisted() throws Exception {
        create(VALID_BODY).andExpect(status().isUnauthorized());

        assertThat(productRepository.count()).isZero();
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        mockMvc.perform(post("/api/products").with(httpBasic("nexora_test", "wrong"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        assertThat(productRepository.count()).isZero();
    }

    @Test
    void httpBasicWithoutCsrfTokenCreatesProduct() throws Exception {
        mockMvc.perform(post("/api/products").with(httpBasic("nexora_test", "nexora-test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());

        assertThat(productRepository.count()).isEqualTo(1);
    }
}
