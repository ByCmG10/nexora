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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs against the isolated in-memory database from src/test/resources/application.properties. */
@SpringBootTest
@AutoConfigureMockMvc
class ProductDeletionTests {

    private static final String SKU = "DEL-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void seedProducts() {
        productRepository.deleteAll();
        productRepository.save(new Product("To Delete", SKU, new BigDecimal("9.99"), 10, 3));
        productRepository.save(new Product("Bystander", "OTHER-1", new BigDecimal("1.00"), 1, 0));
    }

    private ResultActions deleteSku(String sku) throws Exception {
        return mockMvc.perform(delete("/api/products/sku/{sku}", sku));
    }

    private void assertNothingWasDeleted() {
        assertThat(productRepository.count()).isEqualTo(2);
        assertThat(productRepository.existsBySku(SKU)).isTrue();
        assertThat(productRepository.existsBySku("OTHER-1")).isTrue();
    }

    @Test
    @WithMockUser
    void existingProductIsDeletedWithNoContentAndEmptyBody() throws Exception {
        deleteSku(SKU)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(productRepository.existsBySku(SKU)).isFalse();
        assertThat(productRepository.findBySku(SKU)).isEmpty();
        assertThat(productRepository.count()).isEqualTo(1);
    }

    @Test
    @WithMockUser
    void deletionIsPhysicalAndOnlyAffectsTheTargetedProduct() throws Exception {
        Long deletedId = productRepository.findBySku(SKU).orElseThrow().getId();

        deleteSku(SKU).andExpect(status().isNoContent());

        assertThat(productRepository.findById(deletedId)).isEmpty();
        Product bystander = productRepository.findBySku("OTHER-1").orElseThrow();
        assertThat(bystander.getName()).isEqualTo("Bystander");
        assertThat(bystander.getStock()).isEqualTo(1);
    }

    @Test
    @WithMockUser
    void deletedProductIsNoLongerReturnedByGet() throws Exception {
        mockMvc.perform(get("/api/products/sku/{sku}", SKU)).andExpect(status().isOk());

        deleteSku(SKU).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/products/sku/{sku}", SKU)).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void deletingTwiceReturnsNotFoundTheSecondTime() throws Exception {
        deleteSku(SKU).andExpect(status().isNoContent());
        deleteSku(SKU).andExpect(status().isNotFound());

        assertThat(productRepository.count()).isEqualTo(1);
    }

    @Test
    @WithMockUser
    void skuCanBeReusedAfterPermanentDeletion() throws Exception {
        deleteSku(SKU).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Reborn","sku":"DEL-1","price":2.00,"stock":4,"minimumStock":1}
                """)).andExpect(status().isCreated());

        assertThat(productRepository.findBySku(SKU).orElseThrow().getName()).isEqualTo("Reborn");
    }

    @Test
    @WithMockUser
    void unknownSkuReturnsNotFoundAndChangesNothing() throws Exception {
        deleteSku("NO-SUCH-SKU")
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        assertNothingWasDeleted();
        assertThat(productRepository.existsBySku("NO-SUCH-SKU")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"del-1", "Del-1", "  del-1  ", " DEL-1", "DEL-1 "})
    @WithMockUser
    void skuInThePathIsNormalisedToLocateTheProduct(String sku) throws Exception {
        deleteSku(sku).andExpect(status().isNoContent());

        assertThat(productRepository.existsBySku(SKU)).isFalse();
        assertThat(productRepository.existsBySku("OTHER-1")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEL.1", "DEL_1", "DEL-11", "DEL"})
    @WithMockUser
    void similarButDifferentSkusDoNotDeleteTheProduct(String sku) throws Exception {
        deleteSku(sku).andExpect(status().isNotFound());

        assertNothingWasDeleted();
    }

    @Test
    void unauthenticatedRequestIsRejectedAndProductRemains() throws Exception {
        deleteSku(SKU).andExpect(status().isUnauthorized());

        assertNothingWasDeleted();
    }

    @Test
    void wrongCredentialsAreRejectedAndProductRemains() throws Exception {
        mockMvc.perform(delete("/api/products/sku/{sku}", SKU).with(httpBasic("nexora_test", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/products/sku/{sku}", SKU).with(httpBasic("intruder", "nexora-test-password")))
                .andExpect(status().isUnauthorized());

        assertNothingWasDeleted();
    }

    @Test
    void unauthenticatedDeleteOfUnknownSkuIsStillUnauthorizedNotNotFound() throws Exception {
        deleteSku("NO-SUCH-SKU").andExpect(status().isUnauthorized());

        assertNothingWasDeleted();
    }

    @Test
    void httpBasicWithoutCsrfTokenDeletesProduct() throws Exception {
        mockMvc.perform(delete("/api/products/sku/{sku}", SKU)
                        .with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(productRepository.existsBySku(SKU)).isFalse();
        assertThat(productRepository.existsBySku("OTHER-1")).isTrue();
    }
}
