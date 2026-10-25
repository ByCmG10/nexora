package com.nexora.product;

import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
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
    public List<Product> findByStock(Integer stock) {
        return productRepository.findByStockOrderByIdAsc(stock);
    }

    @Transactional(readOnly = true)
    public List<Product> findByMinStock(Integer stock) {
        return productRepository.findByStockGreaterThanEqualOrderByIdAsc(stock);
    }

    @Transactional(readOnly = true)
    public List<Product> findByMaxStock(Integer stock) {
        return productRepository.findByStockLessThanEqualOrderByIdAsc(stock);
    }

    @Transactional(readOnly = true)
    public List<Product> findByMaxPrice(BigDecimal price) {
        return productRepository.findByPriceLessThanEqualOrderByIdAsc(price);
    }

    @Transactional(readOnly = true)
    public List<Product> findByPriceRange(BigDecimal min, BigDecimal max) {
        requireValidPriceRange(min, max);
        return productRepository.findByPriceBetweenOrderByIdAsc(min, max);
    }

    @Transactional(readOnly = true)
    public List<Product> searchByName(String name) {
        return productRepository.findByNameContainingIgnoreCase(name.strip());
    }

    @Transactional(readOnly = true)
    public List<Product> searchByNameAndMinStock(String name, Integer minStock) {
        return productRepository.findByNameContainingIgnoreCaseAndStockGreaterThanEqualOrderByIdAsc(name.strip(),
                minStock);
    }

    @Transactional(readOnly = true)
    public List<Product> searchByNameAndMaxPrice(String name, BigDecimal maxPrice) {
        return productRepository.findByNameContainingIgnoreCaseAndPriceLessThanEqualOrderByIdAsc(name.strip(),
                maxPrice);
    }

    @Transactional(readOnly = true)
    public List<Product> searchByNameAndMaxStock(String name, Integer maxStock) {
        return productRepository.findByNameContainingIgnoreCaseAndStockLessThanEqualOrderByIdAsc(name.strip(),
                maxStock);
    }

    @Transactional(readOnly = true)
    public List<Product> searchByNameAndExactStock(String name, Integer stock) {
        return productRepository.findByNameContainingIgnoreCaseAndStockOrderByIdAsc(name.strip(), stock);
    }

    @Transactional(readOnly = true)
    public List<Product> searchByNameAndPriceRange(String name, BigDecimal minPrice, BigDecimal maxPrice) {
        requireValidPriceRange(minPrice, maxPrice);
        return productRepository.findByNameContainingIgnoreCaseAndPriceBetweenOrderByIdAsc(name.strip(), minPrice,
                maxPrice);
    }

    @Transactional(readOnly = true)
    public List<Product> filterByNameMaxPriceAndMinStock(String name, BigDecimal maxPrice, Integer minStock) {
        return productRepository
                .findByNameContainingIgnoreCaseAndPriceLessThanEqualAndStockGreaterThanEqualOrderByIdAsc(name.strip(),
                        maxPrice, minStock);
    }

    /**
     * Applies only the filters that are present (not null), combined with AND. Without a sort field the order is id
     * ascending; with one, it is that field in the given direction (ascending when null) and then id ascending, so
     * pages stay stable. The order comes from the specification (the pageable must be unsorted). Pagination is done
     * by the database.
     */
    @Transactional(readOnly = true)
    public Page<Product> filterOptional(String name, BigDecimal minPrice, BigDecimal maxPrice, Integer minStock,
            Integer maxStock, ProductSortField sortBy, Sort.Direction direction, Pageable pageable) {
        if (minPrice != null && maxPrice != null) {
            requireValidPriceRange(minPrice, maxPrice);
        }
        if (minStock != null && maxStock != null) {
            requireValidStockRange(minStock, maxStock);
        }
        Specification<Product> spec = ordered(sortBy, direction);
        if (name != null) {
            spec = spec.and(nameContains(name.strip()));
        }
        if (minPrice != null) {
            spec = spec.and(priceAtLeast(minPrice));
        }
        if (maxPrice != null) {
            spec = spec.and(priceAtMost(maxPrice));
        }
        if (minStock != null) {
            spec = spec.and(stockAtLeast(minStock));
        }
        if (maxStock != null) {
            spec = spec.and(stockAtMost(maxStock));
        }
        if (pageable.getOffset() > Integer.MAX_VALUE) {
            // JPA's firstResult is an int and no table is paged beyond it: such a page is necessarily past the
            // last row, so answer with an empty page (and the real total) instead of failing.
            return new PageImpl<>(List.of(), pageable, productRepository.count(spec));
        }
        return productRepository.findAll(spec, pageable);
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

    /**
     * Starting point of every filter: no restriction on the rows, and the one place where the order is defined.
     * The requested field (if any) comes first; id ascending is always the last, unique tie-breaker, so equal
     * prices or stocks never make the order (and therefore the pages) unstable. Without a requested field the
     * order is exactly id ascending.
     */
    private static Specification<Product> ordered(ProductSortField sortBy, Sort.Direction direction) {
        return (root, query, cb) -> {
            List<Order> orders = new ArrayList<>();
            if (sortBy != null) {
                Path<Object> field = root.get(sortBy.attribute());
                orders.add(direction == Sort.Direction.DESC ? cb.desc(field) : cb.asc(field));
            }
            orders.add(cb.asc(root.get("id")));
            query.orderBy(orders);
            return cb.conjunction();
        };
    }

    /** Partial, case-insensitive match (upper(name) like upper('%text%')); LIKE wildcards in the text are literal. */
    private static Specification<Product> nameContains(String text) {
        String pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return (root, query, cb) -> cb.like(cb.upper(root.<String>get("name")), cb.upper(cb.literal(pattern)), '\\');
    }

    private static Specification<Product> priceAtLeast(BigDecimal minPrice) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.<BigDecimal>get("price"), minPrice);
    }

    private static Specification<Product> priceAtMost(BigDecimal maxPrice) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.<BigDecimal>get("price"), maxPrice);
    }

    private static Specification<Product> stockAtLeast(Integer minStock) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.<Integer>get("stock"), minStock);
    }

    private static Specification<Product> stockAtMost(Integer maxStock) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.<Integer>get("stock"), maxStock);
    }

    private static void requireValidStockRange(Integer minStock, Integer maxStock) {
        if (minStock > maxStock) {
            throw new InvalidStockRangeException(minStock, maxStock);
        }
    }

    private static void requireValidPriceRange(BigDecimal min, BigDecimal max) {
        if (min.compareTo(max) > 0) {
            throw new InvalidPriceRangeException(min, max);
        }
    }

    /** SKUs are case-insensitive and ignore surrounding whitespace: stored trimmed and upper-cased. */
    private static String normalizeSku(String sku) {
        return sku.strip().toUpperCase(Locale.ROOT);
    }
}
