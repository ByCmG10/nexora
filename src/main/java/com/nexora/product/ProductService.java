package com.nexora.product;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** SKUs are case-insensitive and ignore surrounding whitespace: stored trimmed and upper-cased. */
    private static String normalizeSku(String sku) {
        return sku.strip().toUpperCase(Locale.ROOT);
    }
}
