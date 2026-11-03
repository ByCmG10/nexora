package com.nexora;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The OpenAPI document and Swagger UI are public; the API they describe is not. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiDocumentationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void openApiDocumentIsPublicAndDescribesTheProductApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("NEXORA API"))
                .andExpect(jsonPath("$.info.version").value("1.0.0"))
                .andExpect(jsonPath("$.paths['/api/products']").exists())
                .andExpect(jsonPath("$.paths['/api/products/filter-optional']").exists())
                .andExpect(jsonPath("$.paths['/api/products/sku/{sku}']").exists())
                .andExpect(jsonPath("$.components.schemas.ProductResponse").exists());
    }

    @Test
    void openApiDeclaresHttpBasicAuthentication() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.securitySchemes.basicAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.basicAuth.scheme").value("basic"))
                .andExpect(jsonPath("$.security[0].basicAuth").isArray());
    }

    @Test
    void swaggerUiIsPublic() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    }

    @Test
    void productApiStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/products/low-stock")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/products/filter-optional")).andExpect(status().isUnauthorized());
    }

    @Test
    void productApiWorksWithHttpBasicCredentials() throws Exception {
        mockMvc.perform(get("/api/products/low-stock").with(httpBasic("nexora_test", "nexora-test-password")))
                .andExpect(status().isOk());
    }
}
