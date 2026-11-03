package com.nexora.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence checks against a real, throwaway PostgreSQL (Testcontainers) instead of the in-memory H2 used by the
 * rest of the suite. The container's connection details replace the H2 ones from application.properties; Flyway
 * applies the real migrations and Hibernate only validates the schema. Needs Docker; nothing else is touched.
 *
 * <pre>
 *  id  sku  name           price  stock
 *   1  P-A  Camiseta A     30.00      5
 *   2  P-B  Camiseta B     10.00     50
 *   3  P-C  Pantalon C     20.00     20
 *   4  P-D  Camiseta D     10.00     20     (price tie with B)
 *   5  P-E  Camiseta E     30.00      5     (ties with A on price and stock)
 * </pre>
 */
@SpringBootTest
@Testcontainers
class ProductPostgresIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        productRepository.deleteAll();
        productService.create(request("P-A", "Camiseta A", "30.00", 5));
        productService.create(request("P-B", "Camiseta B", "10.00", 50));
        productService.create(request("P-C", "Pantalon C", "20.00", 20));
        productService.create(request("P-D", "Camiseta D", "10.00", 20));
        productService.create(request("P-E", "Camiseta E", "30.00", 5));
    }

    @Test
    void flywayAppliedTheRealMigrationOnPostgres() {
        assertThat(jdbcTemplate.queryForObject("select version()", String.class)).startsWith("PostgreSQL 18");
        assertThat(jdbcTemplate.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class))
                .containsExactly("1");
        assertThat(productRepository.count()).isEqualTo(5);
    }

    @Test
    void skuUniquenessIsEnforcedByThePostgresConstraint() {
        assertThatThrownBy(() -> productService.create(request("p-a", "Other", "1.00", 1)))
                .isInstanceOf(DuplicateSkuException.class);

        // Bypassing the service check: the database itself must reject the duplicate.
        assertThatThrownBy(() -> productRepository.saveAndFlush(
                new Product("Other", "P-A", new BigDecimal("1.00"), 1, 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(productRepository.count()).isEqualTo(5);
    }

    @Test
    void combinedFilterReturnsExactlyTheMatchingProducts() {
        var page = productService.filterOptional("camiseta", new BigDecimal("10.00"), new BigDecimal("30.00"), 5, 20,
                null, null, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Product::getSku).containsExactly("P-A", "P-D", "P-E");
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void sortingByPriceDescendingBreaksTiesByIdAscending() {
        var page = productService.filterOptional(null, null, null, null, null, ProductSortField.PRICE,
                Sort.Direction.DESC, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Product::getSku).containsExactly("P-A", "P-E", "P-C", "P-B", "P-D");
    }

    @Test
    void sortingByStockAscendingIsPagedByThePostgresDatabase() {
        var first = productService.filterOptional(null, null, null, null, null, ProductSortField.STOCK,
                Sort.Direction.ASC, PageRequest.of(0, 2));
        var second = productService.filterOptional(null, null, null, null, null, ProductSortField.STOCK,
                Sort.Direction.ASC, PageRequest.of(1, 2));

        assertThat(first.getContent()).extracting(Product::getSku).containsExactly("P-A", "P-E");
        assertThat(second.getContent()).extracting(Product::getSku).containsExactly("P-C", "P-D");
        assertThat(first.getTotalElements()).isEqualTo(5);
    }

    private static CreateProductRequest request(String sku, String name, String price, int stock) {
        return new CreateProductRequest(name, sku, new BigDecimal(price), stock, 0);
    }
}
