package com.example.orderservice.scheduler;

import com.example.orderservice.dao.ProductDao;
import com.example.orderservice.entity.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockSyncScheduler {

    private static final String STOCK_KEY_PREFIX = "product:stock:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ProductDao productDao;

    @Scheduled(fixedRate = 30000)
    public void syncStock() {
        log.info(">>>>>开始同步redis商品库存到数据库...");
        // 读取 Redis 库存
        Set<String> keys = stringRedisTemplate.keys(STOCK_KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) return;

        Map<Long, Integer> redisStockMap = new HashMap<>();
        for (String key : keys) {
            try {
                Long productId = Long.parseLong(key.substring(STOCK_KEY_PREFIX.length()));
                String stockStr = stringRedisTemplate.opsForValue().get(key);
                if (stockStr != null) {
                    redisStockMap.put(productId, Integer.parseInt(stockStr));
                }
            } catch (Exception e) {
                log.error("读取 Redis 库存失败 key={}", key, e);
            }
        }
        if (redisStockMap.isEmpty()) return;

        // 一次查出所有商品
        List<Product> allProducts = productDao.selectList(null);
        if (allProducts.isEmpty()) return;

        // 找出需要更新的一致商品
        List<Product> needUpdate = new ArrayList<>();
        for (Product product : allProducts) {
            Integer redisStock = redisStockMap.get(product.getId());
            if (redisStock != null && !redisStock.equals(product.getStock())) {
                product.setStock(redisStock);
                needUpdate.add(product);
            }
        }

        // 一次批量更新
        if (!needUpdate.isEmpty()) {
            productDao.batchUpdateStock(needUpdate);
        }
        log.info(">>>>>同步redis商品数据完成：{} 个商品已更新", needUpdate.size());
    }
}
