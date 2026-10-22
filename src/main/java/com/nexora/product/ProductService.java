package com.nexora.product;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Optional<Product> findBySku(String sku) {
        return productRepository.findBySku(normalizeSku(sku));
    }

    @Transactional(readOnly = true)
    public List<Product> findLowStock() {
        return productRepository.findLowStock();
    }

    @Transactional(readOnly = true)
    public List<Product> findOutOfStock() {
        return productRepository.findByStockOrderByIdAsc(0);
    }

    @Transactional(readOnly = true)
    public List<Product> findByMaxPrice(BigDecimal price) {
        return productRepository.findByPriceLessThanEqualOrderByIdAsc(price);
    }

    @Transactional(readOnly = true)
    public List<Product> findByPriceRange(BigDecimal min, BigDecimal max) {
        if (min.compareTo(max) > 0) {
            throw new InvalidPriceRangeException(min, max);
        }
        return productRepository.findByPriceBetweenOrderByIdAsc(min, max);
    }

    @Transactional(readOnly = true)
    public List<Product> searchByName(String name) {
        return productRepository.findByNameContainingIgnoreCase(name.strip());
    }

    public Product create(CreateProductRequest request) {
        String sku = normalizeSku(request.sku());
        if (productRepository.existsBySku(sku)) {
            throw new DuplicateSkuException(sku);
        }
        Product product = new Product(request.name(), sku, request.price(), request.stock(), request.minimumStock());
        try {
            return productRepository.save(product);
        } catch (DataIntegrityViolationException e) {
            // Only a duplicate when the SKU now exists (a concurrent request inserted it between the check
            // and the insert); any other integrity violation is propagated as the server error it is.
            if (productRepository.existsBySku(sku)) {
                throw new DuplicateSkuException(sku);
            }
            throw e;
        }
    }

    @Transactional
    public Optional<Product> updateStock(String sku, Integer stock) {
        return productRepository.findBySku(normalizeSku(sku)).map(product -> {
            product.updateStock(stock);
            return productRepository.save(product);
        });
    }

    @Transactional
    public Optional<Product> updatePrice(String sku, BigDecimal price) {
        return productRepository.findBySku(normalizeSku(sku)).map(product -> {
            product.updatePrice(price);
            return productRepository.save(product);
        });
    }

    /** Permanently deletes the product; returns false when no product has that (normalised) SKU. */
    @Transactional
    public boolean deleteBySku(String sku) {
        return productRepository.findBySku(normalizeSku(sku)).map(product -> {
            productRepository.delete(product);
            return true;
        }).orElse(false);
    }

    /** SKUs are case-insensitive and ignore surrounding whitespace: stored trimmed and upper-cased. */
    private static String normalizeSku(String sku) {
        return sku.strip().toUpperCase(Locale.ROOT);
    }
}
