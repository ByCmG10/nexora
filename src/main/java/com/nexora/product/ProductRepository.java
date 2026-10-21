package com.nexora.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    List<Product> findByNameContainingIgnoreCase(String name);

    List<Product> findByStockOrderByIdAsc(Integer stock);

    List<Product> findByPriceLessThanEqualOrderByIdAsc(BigDecimal price);

    @Query("select p from Product p where p.stock <= p.minimumStock order by p.id")
    List<Product> findLowStock();
}
