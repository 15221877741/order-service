package com.example.orderservice.service;

import com.example.orderservice.dao.ProductDao;
import com.example.orderservice.entity.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductDao productDao;
    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String PRODUCT_CACHE_KEY = "product:";
    private static final String STOCK_KEY_PREFIX = "product:stock:";
    private static final long CACHE_EXPIRE_SECONDS = 3600;

    private DefaultRedisScript<Long> reduceStockScript;

    @PostConstruct
    public void init() {
        reduceStockScript = new DefaultRedisScript<>();
        reduceStockScript.setLocation(new ClassPathResource("lua/reduceStock.lua"));
        reduceStockScript.setResultType(Long.class);

        List<Product> products = productDao.selectList(null);
        for (Product product : products) {
            cacheProduct(product);
        }
        log.info("缓存初始化完成，共 {} 个商品", products.size());
    }

    public List<Product> listProducts() {
        List<Product> products = productDao.selectList(null);
        products.forEach(this::cacheProduct);
        return products;
    }

    public Product getProduct(Long id) {
        String key = PRODUCT_CACHE_KEY + id;
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            Product product;
            if (cached instanceof Product) {
                product = (Product) cached;
            } else {
                product = objectMapper.convertValue(cached, Product.class);
            }
            if (product != null) {
                String stockStr = stringRedisTemplate.opsForValue().get(STOCK_KEY_PREFIX + id);
                if (stockStr != null) {
                    product.setStock(Integer.parseInt(stockStr));
                }
            }
            return product;
        }
        Product product = productDao.selectById(id);
        if (product != null) {
            cacheProduct(product);
        }
        return product;
    }

    public boolean reduceStockAtomic(Long productId, Integer quantity) {
        Long result = stringRedisTemplate.execute(
            reduceStockScript,
            List.of(STOCK_KEY_PREFIX + productId, PRODUCT_CACHE_KEY + productId),
            String.valueOf(quantity)
        );
        if (result == null) return false;
        if (result == -1) {
            throw new RuntimeException("商品不存在或缓存未预热: " + productId);
        }
        return result == 1;
    }

    public boolean reduceStock(Long productId, Integer quantity) {
        return reduceStockAtomic(productId, quantity);
    }

    private void cacheProduct(Product product) {
        String key = PRODUCT_CACHE_KEY + product.getId();
        redisTemplate.opsForValue().set(key, product, CACHE_EXPIRE_SECONDS, TimeUnit.SECONDS);
        stringRedisTemplate.opsForValue().set(STOCK_KEY_PREFIX + product.getId(), String.valueOf(product.getStock()));
    }
}