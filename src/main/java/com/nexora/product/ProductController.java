package com.nexora.product;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
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
    public List<Product> findLowStock() {
        return productService.findLowStock();
    }

    @GetMapping("/by-stock")
    public List<Product> findByStock(@RequestParam @Min(0) Integer stock) {
        return productService.findByStock(stock);
    }

    @GetMapping("/by-min-stock")
    public List<Product> findByMinStock(@RequestParam @Min(0) Integer stock) {
        return productService.findByMinStock(stock);
    }

    @GetMapping("/by-max-stock")
    public List<Product> findByMaxStock(@RequestParam @Min(0) Integer stock) {
        return productService.findByMaxStock(stock);
    }

    @GetMapping("/by-max-price")
    public List<Product> findByMaxPrice(@RequestParam @Positive BigDecimal price) {
        return productService.findByMaxPrice(price);
    }

    @GetMapping("/by-price-range")
    public List<Product> findByPriceRange(@RequestParam @Positive BigDecimal min,
            @RequestParam @Positive BigDecimal max) {
        return productService.findByPriceRange(min, max);
    }

    @GetMapping("/out-of-stock")
    public List<Product> findOutOfStock() {
        return productService.findOutOfStock();
    }

    @GetMapping("/search")
    public List<Product> searchByName(@RequestParam @NotBlank String name) {
        return productService.searchByName(name);
    }

    @GetMapping("/search-by-stock")
    public List<Product> searchByNameAndMinStock(@RequestParam @NotBlank String name,
            @RequestParam @Min(0) Integer minStock) {
        return productService.searchByNameAndMinStock(name, minStock);
    }

    @GetMapping("/search-by-price")
    public List<Product> searchByNameAndMaxPrice(@RequestParam @NotBlank String name,
            @RequestParam @Positive BigDecimal maxPrice) {
        return productService.searchByNameAndMaxPrice(name, maxPrice);
    }

    @GetMapping("/search-by-max-stock")
    public List<Product> searchByNameAndMaxStock(@RequestParam @NotBlank String name,
            @RequestParam @Min(0) Integer maxStock) {
        return productService.searchByNameAndMaxStock(name, maxStock);
    }

    @GetMapping("/search-by-exact-stock")
    public List<Product> searchByNameAndExactStock(@RequestParam @NotBlank String name,
            @RequestParam @Min(0) Integer stock) {
        return productService.searchByNameAndExactStock(name, stock);
    }

    @GetMapping("/search-by-price-range")
    public List<Product> searchByNameAndPriceRange(@RequestParam @NotBlank String name,
            @RequestParam @Positive BigDecimal minPrice, @RequestParam @Positive BigDecimal maxPrice) {
        return productService.searchByNameAndPriceRange(name, minPrice, maxPrice);
    }

    @GetMapping("/sku/{sku}")
    public ResponseEntity<Product> findBySku(@PathVariable String sku) {
        return productService.findBySku(sku)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/sku/{sku}/stock")
    public ResponseEntity<Product> updateStock(@PathVariable String sku,
            @Valid @RequestBody UpdateStockRequest request) {
        return productService.updateStock(sku, request.stock())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/sku/{sku}/price")
    public ResponseEntity<Product> updatePrice(@PathVariable String sku,
            @Valid @RequestBody UpdatePriceRequest request) {
        return productService.updatePrice(sku, request.price())
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
    public ResponseEntity<Product> create(@Valid @RequestBody CreateProductRequest request) {
        Product created = productService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/products/sku/{sku}")
                .encode() // strict encoding of the SKU variable (spaces, '?', '#', '%', '/'...)
                .buildAndExpand(created.getSku())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }
}
