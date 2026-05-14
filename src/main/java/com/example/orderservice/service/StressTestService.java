package com.example.orderservice.service;

import com.example.orderservice.dao.ProductDao;
import com.example.orderservice.entity.Product;
import com.example.orderservice.entity.StressTestTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class StressTestService {

    private final OrderService orderService;
    private final ProductDao productDao;
    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    private final Map<String, StressTestTask> tasks = new ConcurrentHashMap<>();

    private static final String STOCK_KEY_PREFIX = "product:stock:";
    private static final String PRODUCT_CACHE_KEY = "product:";
    private static final long CACHE_EXPIRE_SECONDS = 3600;
    private static final long MAX_TIMEOUT_MINUTES = 30;

    public String submitTask(StressTestTask task) {
        task.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        task.setStatus(StressTestTask.STATUS_RUNNING);
        task.setStartTime(System.currentTimeMillis());
        tasks.put(task.getTaskId(), task);

        CompletableFuture.runAsync(() -> executeTask(task))
                .exceptionally(e -> {
                    log.error("压测任务执行异常 taskId={}", task.getTaskId(), e);
                    task.setStatus(StressTestTask.STATUS_FAILED);
                    task.setEndTime(System.currentTimeMillis());
                    return null;
                });

        return task.getTaskId();
    }

    private void executeTask(StressTestTask task) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                task.getConcurrency(), task.getConcurrency(),
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(task.getTotalRequests()),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        CountDownLatch latch = new CountDownLatch(task.getTotalRequests());

        for (int i = 0; i < task.getTotalRequests(); i++) {
            executor.submit(() -> {
                long start = System.currentTimeMillis();
                try {
                    orderService.createOrder(task.getUserId(),
                            List.of(task.getProductId()),
                            List.of(task.getQuantity()));
                    task.addSuccess(System.currentTimeMillis() - start);
                } catch (Exception e) {
                    task.addError(e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(MAX_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        executor.shutdown();
        task.setStatus(StressTestTask.STATUS_COMPLETED);
        task.setEndTime(System.currentTimeMillis());
        log.info("压测任务完成 taskId={}, 成功={}, 失败={}, TPS={}",
                task.getTaskId(), task.getSuccessCount(), task.getFailCount(),
                String.format("%.2f", task.getTps()));
    }

    public StressTestTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    public void resetStock() {
        List<Product> products = productDao.selectList(null);
        for (Product product : products) {
            stringRedisTemplate.opsForValue().set(
                    STOCK_KEY_PREFIX + product.getId(),
                    String.valueOf(product.getStock()));
            redisTemplate.opsForValue().set(
                    PRODUCT_CACHE_KEY + product.getId(),
                    product, CACHE_EXPIRE_SECONDS, TimeUnit.SECONDS);
        }
        log.info("库存已重置，共 {} 个商品", products.size());
    }

    @Scheduled(fixedRate = 60000)
    public void cleanupExpiredTasks() {
        long now = System.currentTimeMillis();
        tasks.entrySet().removeIf(entry -> {
            StressTestTask task = entry.getValue();
            if (task.getStatus() == StressTestTask.STATUS_RUNNING) return false;
            return (now - task.getEndTime()) > 5 * 60 * 1000;
        });
    }
}
