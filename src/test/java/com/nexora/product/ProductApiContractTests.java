package com.nexora.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP boundary: the JSON shape of ProductResponse and the status/body of the common error cases. */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ProductApiContractTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private ProductRepository productRepository;

    @BeforeEach
    void seed() {
        productRepository.deleteAll();
        productRepository.save(new Product("Camiseta", "CT-1", new BigDecimal("12.50"), 3, 5));
    }

    @Test
    void noControllerMethodReturnsTheJpaEntity() {
        Method[] handlers = Arrays.stream(ProductController.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers())).toArray(Method[]::new);

        assertThat(handlers).isNotEmpty().allSatisfy(m -> assertThat(m.getGenericReturnType().getTypeName())
                .as(m.getName()).doesNotContain("com.nexora.product.Product<").doesNotContain("com.nexora.product.Product>")
                .doesNotEndWith("com.nexora.product.Product"));
        assertThat(ProductController.class.getAnnotation(RequestMapping.class)).isNotNull();
    }

    @Test
    void singleProductHasExactlyTheDocumentedFields() throws Exception {
        mockMvc.perform(get("/api/products/sku/ct-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(6)))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Camiseta"))
                .andExpect(jsonPath("$.sku").value("CT-1"))
                .andExpect(jsonPath("$.price").value(12.5))
                .andExpect(jsonPath("$.stock").value(3))
                .andExpect(jsonPath("$.minimumStock").value(5));
    }

    @Test
    void listsAndPagesUseTheSameShape() throws Exception {
        mockMvc.perform(get("/api/products/low-stock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].*", hasSize(6)))
                .andExpect(jsonPath("$[0].sku").value("CT-1"));
        mockMvc.perform(get("/api/products/filter-optional"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].*", hasSize(6)))
                .andExpect(jsonPath("$.content[0].minimumStock").value(5))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void createdProductIsReturnedAsResponse() throws Exception {
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Gorra\",\"sku\":\"gr-1\",\"price\":9.99,\"stock\":1,\"minimumStock\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.*", hasSize(6)))
                .andExpect(jsonPath("$.sku").value("GR-1"));
    }

    @Test
    void invalidParameterTypeIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/products/by-stock").param("stock", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void malformedJsonIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void validationErrorsListTheInvalidFields() throws Exception {
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"sku\":\"ok\",\"price\":-1,\"stock\":1,\"minimumStock\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.price").exists());
    }

    @Test
    void unknownSkuAndDuplicateSkuKeepTheirStatus() throws Exception {
        mockMvc.perform(get("/api/products/sku/NOPE")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"sku\":\"CT-1\",\"price\":1,\"stock\":1,\"minimumStock\":0}"))
                .andExpect(status().isConflict());
    }

    @Test
    void unexpectedErrorIsAGenericServerErrorWithoutInternalDetails() throws Exception {
        doThrow(new IllegalStateException("secret internal detail jdbc:postgresql://db/prod"))
                .when(productRepository).findLowStock();

        mockMvc.perform(get("/api/products/low-stock"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("at com.nexora"))));
    }
}
