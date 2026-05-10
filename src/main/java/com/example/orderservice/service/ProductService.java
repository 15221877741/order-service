package com.example.orderservice.service;

import com.example.orderservice.dao.ProductDao;
import com.example.orderservice.entity.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductDao productDao;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String PRODUCT_CACHE_KEY = "product:";
    private static final long CACHE_EXPIRE_SECONDS = 3600;

    public List<Product> listProducts() {
        List<Product> products = productDao.selectList(null);
        products.forEach(this::cacheProduct);
        return products;
    }

    public Product getProduct(Long id) {
        String key = PRODUCT_CACHE_KEY + id;
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            if (cached instanceof Product) {
                return (Product) cached;
            }
            return objectMapper.convertValue(cached, Product.class);
        }
        Product product = productDao.selectById(id);
        if (product != null) {
            cacheProduct(product);
        }
        return product;
    }

    public boolean reduceStock(Long productId, Integer quantity) {
        String lockKey = "stock:lock:" + productId;
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, java.util.concurrent.TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(locked)) {
            try {
                Product product = getProduct(productId);
                if (product == null || product.getStock() < quantity) {
                    return false;
                }
                product.setStock(product.getStock() - quantity);
                productDao.updateById(product);
                redisTemplate.delete(PRODUCT_CACHE_KEY + productId);
                return true;
            } finally {
                redisTemplate.delete(lockKey);
            }
        }
        return false;
    }

    private void cacheProduct(Product product) {
        String key = PRODUCT_CACHE_KEY + product.getId();
        redisTemplate.opsForValue().set(key, product, CACHE_EXPIRE_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }
}