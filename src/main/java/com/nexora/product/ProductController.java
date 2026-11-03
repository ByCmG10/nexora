package com.nexora.product;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/low-stock")
    public List<ProductResponse> findLowStock() {
        return toResponses(productService.findLowStock());
    }

    @GetMapping("/by-stock")
    public List<ProductResponse> findByStock(@RequestParam @Min(0) Integer stock) {
        return toResponses(productService.findByStock(stock));
    }

    @GetMapping("/by-min-stock")
    public List<ProductResponse> findByMinStock(@RequestParam @Min(0) Integer stock) {
        return toResponses(productService.findByMinStock(stock));
    }

    @GetMapping("/by-max-stock")
    public List<ProductResponse> findByMaxStock(@RequestParam @Min(0) Integer stock) {
        return toResponses(productService.findByMaxStock(stock));
    }

    @GetMapping("/by-max-price")
    public List<ProductResponse> findByMaxPrice(@RequestParam @Positive BigDecimal price) {
        return toResponses(productService.findByMaxPrice(price));
    }

    @GetMapping("/by-price-range")
    public List<ProductResponse> findByPriceRange(@RequestParam @Positive BigDecimal min,
            @RequestParam @Positive BigDecimal max) {
        return toResponses(productService.findByPriceRange(min, max));
    }

    @GetMapping("/out-of-stock")
    public List<ProductResponse> findOutOfStock() {
        return toResponses(productService.findOutOfStock());
    }

    @GetMapping("/search-by-sku")
    public List<ProductResponse> searchBySku(@RequestParam @NotBlank String sku) {
        return toResponses(productService.searchBySku(sku));
    }

    @GetMapping("/search")
    public List<ProductResponse> searchByName(@RequestParam @NotBlank String name) {
        return toResponses(productService.searchByName(name));
    }

    @GetMapping("/search-by-stock")
    public List<ProductResponse> searchByNameAndMinStock(@RequestParam @NotBlank String name,
            @RequestParam @Min(0) Integer minStock) {
        return toResponses(productService.searchByNameAndMinStock(name, minStock));
    }

    @GetMapping("/search-by-price")
    public List<ProductResponse> searchByNameAndMaxPrice(@RequestParam @NotBlank String name,
            @RequestParam @Positive BigDecimal maxPrice) {
        return toResponses(productService.searchByNameAndMaxPrice(name, maxPrice));
    }

    @GetMapping("/search-by-max-stock")
    public List<ProductResponse> searchByNameAndMaxStock(@RequestParam @NotBlank String name,
            @RequestParam @Min(0) Integer maxStock) {
        return toResponses(productService.searchByNameAndMaxStock(name, maxStock));
    }

    @GetMapping("/search-by-exact-stock")
    public List<ProductResponse> searchByNameAndExactStock(@RequestParam @NotBlank String name,
            @RequestParam @Min(0) Integer stock) {
        return toResponses(productService.searchByNameAndExactStock(name, stock));
    }

    @GetMapping("/search-by-price-range")
    public List<ProductResponse> searchByNameAndPriceRange(@RequestParam @NotBlank String name,
            @RequestParam @Positive BigDecimal minPrice, @RequestParam @Positive BigDecimal maxPrice) {
        return toResponses(productService.searchByNameAndPriceRange(name, minPrice, maxPrice));
    }

    @GetMapping("/filter")
    public List<ProductResponse> filterByNameMaxPriceAndMinStock(@RequestParam @NotBlank String name,
            @RequestParam @Positive BigDecimal maxPrice, @RequestParam @Min(0) Integer minStock) {
        return toResponses(productService.filterByNameMaxPriceAndMinStock(name, maxPrice, minStock));
    }

    /**
     * The general, recommended way to filter: every filter is optional and combinable, with pagination and sorting.
     * The specific endpoints above (by-*, search-*, filter) are kept for compatibility and each has a fixed meaning.
     */
    @GetMapping("/filter-optional")
    public PagedModel<ProductResponse> filterOptional(@RequestParam(required = false) String name,
            @RequestParam(required = false) @Positive BigDecimal minPrice,
            @RequestParam(required = false) @Positive BigDecimal maxPrice,
            @RequestParam(required = false) @Min(0) Integer minStock,
            @RequestParam(required = false) @Min(0) Integer maxStock,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Pattern(regexp = "(?i)price|stock",
                    message = "must be one of: price, stock") String sortBy,
            @RequestParam(required = false) @Pattern(regexp = "(?i)asc|desc",
                    message = "must be one of: asc, desc") String direction) {
        // @NotBlank cannot be used here: it rejects null, and an absent name is valid.
        if (name != null && name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must not be blank when provided");
        }
        if (direction != null && sortBy == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "direction requires sortBy");
        }
        Sort.Direction sortDirection = direction == null ? Sort.Direction.ASC : Sort.Direction.fromString(direction);
        // The pageable is unsorted on purpose: the order is defined once, in the service's specification.
        return new PagedModel<>(productService.filterOptional(name, minPrice, maxPrice, minStock, maxStock,
                ProductSortField.fromParameter(sortBy), sortDirection, PageRequest.of(page, size))
                .map(ProductResponse::from));
    }

    @GetMapping("/sku/{sku}")
    public ResponseEntity<ProductResponse> findBySku(@PathVariable String sku) {
        return productService.findBySku(sku)
                .map(ProductResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/sku/{sku}/stock")
    public ResponseEntity<ProductResponse> updateStock(@PathVariable String sku,
            @Valid @RequestBody UpdateStockRequest request) {
        return productService.updateStock(sku, request.stock())
                .map(ProductResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/sku/{sku}/price")
    public ResponseEntity<ProductResponse> updatePrice(@PathVariable String sku,
            @Valid @RequestBody UpdatePriceRequest request) {
        return productService.updatePrice(sku, request.price())
                .map(ProductResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/sku/{sku}")
    public ResponseEntity<Void> deleteBySku(@PathVariable String sku) {
        return productService.deleteBySku(sku)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse created = ProductResponse.from(productService.create(request));
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/products/sku/{sku}")
                .encode() // strict encoding of the SKU variable (spaces, '?', '#', '%', '/'...)
                .buildAndExpand(created.sku())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    private static List<ProductResponse> toResponses(List<Product> products) {
        return products.stream().map(ProductResponse::from).toList();
    }
}
