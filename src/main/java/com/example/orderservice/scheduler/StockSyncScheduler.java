package com.example.orderservice.scheduler;

import com.example.orderservice.dao.ProductDao;
import com.example.orderservice.entity.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockSyncScheduler {

    private static final String STOCK_KEY_PREFIX = "product:stock:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ProductDao productDao;

    @Scheduled(fixedRate = 300000)
    public void syncStock() {
        log.info(">>>>>开始同步redis商品库存到数据库...");
        Set<String> keys = stringRedisTemplate.keys(STOCK_KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) return;

        for (String key : keys) {
            try {
                Long productId = Long.parseLong(key.substring(STOCK_KEY_PREFIX.length()));
                String stockStr = stringRedisTemplate.opsForValue().get(key);
                if (stockStr == null) continue;

                int redisStock = Integer.parseInt(stockStr);
                Product product = productDao.selectById(productId);
                if (product != null && product.getStock() != redisStock) {
                    product.setStock(redisStock);
                    productDao.updateById(product);
                }
            } catch (Exception e) {
                log.error("同步库存失败 key={}", key, e);
            }
        }
        log.info(">>>>>同步redis商品数据完成...");
    }
}
