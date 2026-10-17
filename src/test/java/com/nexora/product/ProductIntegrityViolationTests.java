package com.nexora.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A DataIntegrityViolationException is a 409 only when the SKU really is a duplicate. */
@SpringBootTest
@AutoConfigureMockMvc
class ProductIntegrityViolationTests {

    private static final String BODY = """
            {"name":"P","sku":"RACE-1","price":1.00,"stock":0,"minimumStock":0}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private ProductRepository productRepository;

    @BeforeEach
    void cleanDatabase() {
        productRepository.deleteAll();
    }

    @Test
    @WithMockUser
    void concurrentDuplicateInsertedBetweenCheckAndSaveReturnsConflict() throws Exception {
        productRepository.save(new Product("First", "RACE-1", java.math.BigDecimal.ONE, 0, 0));
        // The pre-insert check misses the existing row (as if inserted concurrently), the real unique constraint
        // fires on save, and the re-check after the failure sees the row.
        doReturn(false).doReturn(true).when(productRepository).existsBySku("RACE-1");

        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());

        assertThat(productRepository.count()).isEqualTo(1);
    }

    @Test
    @WithMockUser
    void otherIntegrityViolationsAreNotReportedAsDuplicateSku() {
        doThrow(new DataIntegrityViolationException("not null violation on another column"))
                .when(productRepository).save(any(Product.class));

        // Propagated as a server error (500 over HTTP), never converted into a 409.
        assertThatThrownBy(() -> mockMvc.perform(
                post("/api/products").contentType(MediaType.APPLICATION_JSON).content(BODY)))
                .hasRootCauseInstanceOf(DataIntegrityViolationException.class);
        assertThat(productRepository.existsBySku("RACE-1")).isFalse();
    }
}
