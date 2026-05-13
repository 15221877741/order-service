package com.example.orderservice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.orderservice.dao.OrderDao;
import com.example.orderservice.dao.OrderItemDao;
import com.example.orderservice.entity.Order;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderItemMessage;
import com.example.orderservice.entity.OrderMessage;
import com.example.orderservice.entity.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderDao orderDao;
    private final OrderItemDao orderItemDao;
    private final ProductService productService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    private static final String ORDER_QUEUE = "order.create";
    private static final String STATS_CACHE_KEY = "order:stats:";

    private void clearStatsCache(Long userId) {
        redisTemplate.delete(STATS_CACHE_KEY + userId);
    }

    /**
     * 创建订单（异步落库）
     * 1. 遍历商品，Lua 原子扣减 Redis 库存
     * 2. 构建 OrderMessage 发到 MQ
     * 3. MQ 发送失败时补偿恢复 Redis 库存
     * 4. 无 DB 写入，无 @Transactional
     */
    public String createOrder(Long userId, List<?> productIds, List<?> quantities) {
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<OrderItemMessage> itemMessages = new ArrayList<>();

        // 第一步：检查库存 + Lua 原子扣减
        for (int i = 0; i < productIds.size(); i++) {
            Long productId = ((Number) productIds.get(i)).longValue();
            Integer quantity = ((Number) quantities.get(i)).intValue();
            Product product = productService.getProduct(productId);
            if (product == null) {
                throw new RuntimeException("商品不存在: " + productId);
            }
            if (product.getStock() < quantity) {
                throw new RuntimeException("库存不足: " + product.getName());
            }
            boolean stockReduced = productService.reduceStockAtomic(productId, quantity);
            if (!stockReduced) {
                throw new RuntimeException("扣减库存失败: " + product.getName());
            }
            totalAmount = totalAmount.add(product.getPrice().multiply(BigDecimal.valueOf(quantity)));

            OrderItemMessage itemMsg = new OrderItemMessage();
            itemMsg.setProductId(productId);
            itemMsg.setProductName(product.getName());
            itemMsg.setPrice(product.getPrice());
            itemMsg.setQuantity(quantity);
            itemMessages.add(itemMsg);
        }

        // 第二步：构建 OrderMessage 发到 MQ
        String orderNo = UUID.randomUUID().toString().replace("-", "");
        OrderMessage msg = new OrderMessage();
        msg.setOrderNo(orderNo);
        msg.setUserId(userId);
        msg.setTotalAmount(totalAmount);
        msg.setStatus(0);
        msg.setCreateTime(LocalDateTime.now());
        msg.setItems(itemMessages);

        try {
            rabbitTemplate.convertAndSend(ORDER_QUEUE, msg);
        } catch (Exception e) {
            // MQ 发送失败 → 补偿恢复 Redis 库存
            log.error("MQ 发送失败，正在补偿恢复库存 orderNo={}", orderNo, e);
            for (int i = 0; i < productIds.size(); i++) {
                Long productId = ((Number) productIds.get(i)).longValue();
                Integer quantity = ((Number) quantities.get(i)).intValue();
                productService.restoreStockAtomic(productId, quantity);
            }
            throw new RuntimeException("订单创建失败，MQ 不可用");
        }

        clearStatsCache(userId);

        return orderNo;
    }

    public Order getOrder(Long orderId) {
        return orderDao.selectById(orderId);
    }

    public List<Order> getUserOrders(Long userId) {
        QueryWrapper<Order> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        wrapper.orderByDesc("create_time");
        List<Order> orders = orderDao.selectList(wrapper);
        batchEnrichOrders(orders);
        return orders;
    }

    public PageInfo<Order> getUserOrders(Long userId, Integer status, int page, int size) {
        log.info("分页查询订单 userId={}, status={}, page={}, size={}", userId, status, page, size);
        PageHelper.startPage(page, size);
        List<Order> orders = orderDao.selectUserOrders(userId, status);
        batchEnrichOrders(orders);
        return new PageInfo<>(orders);
    }

    public Map<String, Long> getOrderStats(Long userId) {
        String key = STATS_CACHE_KEY + userId;
        @SuppressWarnings("unchecked")
        Map<String, Long> cached = (Map<String, Long>) redisTemplate.opsForValue().get(key);
        if (cached != null) return cached;

        List<Map<String, Object>> rows = orderDao.selectMaps(
                new QueryWrapper<Order>()
                        .select("COALESCE(SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END), 0) as pending",
                                "COALESCE(SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END), 0) as completed",
                                "COALESCE(SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END), 0) as cancelled",
                                "COUNT(*) as total")
                        .eq("user_id", userId));
        Map<String, Object> row = rows.get(0);
        long total = ((Number) row.get("total")).longValue();
        long pending = ((Number) row.get("pending")).longValue();
        long completed = ((Number) row.get("completed")).longValue();
        long cancelled = ((Number) row.get("cancelled")).longValue();
        Map<String, Long> stats = Map.of("total", total, "pending", pending, "completed", completed, "cancelled", cancelled);
        redisTemplate.opsForValue().set(key, stats, 30, TimeUnit.SECONDS);
        return stats;
    }

    private void enrichOrderItems(Order order) {
        List<OrderItem> items = orderItemDao.selectList(
                new QueryWrapper<OrderItem>().eq("order_id", order.getId()));
        order.setProductNames(items.stream().map(OrderItem::getProductName).collect(Collectors.joining(", ")));
        order.setTotalQuantity(items.stream().mapToInt(OrderItem::getQuantity).sum());
    }

    private void batchEnrichOrders(List<Order> orders) {
        if (orders.isEmpty()) return;
        List<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toList());
        List<OrderItem> allItems = orderItemDao.selectList(
                new QueryWrapper<OrderItem>().in("order_id", orderIds));
        Map<Long, List<OrderItem>> itemsByOrder = allItems.stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        for (Order order : orders) {
            List<OrderItem> items = itemsByOrder.getOrDefault(order.getId(), List.of());
            order.setProductNames(items.stream().map(OrderItem::getProductName).collect(Collectors.joining(", ")));
            order.setTotalQuantity(items.stream().mapToInt(OrderItem::getQuantity).sum());
        }
    }

    @Transactional
    public void updateOrderStatus(Long orderId, Integer status) {
        Order order = orderDao.selectById(orderId);
        if (order != null) {
            order.setStatus(status);
            order.setUpdateTime(LocalDateTime.now());
            orderDao.updateById(order);
            clearStatsCache(order.getUserId());
        }
    }

    public void deleteOrder(Long orderId, Long userId) {
        Order order = orderDao.selectById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            throw new RuntimeException("无权删除");
        }
        orderDao.deleteById(orderId);
        clearStatsCache(userId);
    }

    public void batchDeleteOrders(List<?> orderIds, Long userId) {
        List<Long> validIds = new ArrayList<>();

        for (Object id : orderIds) {
            Long orderId = ((Number) id).longValue();
            Order order = orderDao.selectById(orderId);
            if (order == null) {
                throw new RuntimeException("订单不存在: " + orderId);
            }
            if (!order.getUserId().equals(userId)) {
                throw new RuntimeException("无权删除");
            }
            if (order.getStatus() != 2) {
                throw new RuntimeException("只能删除已取消的订单");
            }
            validIds.add(orderId);
        }

        orderDao.deleteBatchIds(validIds);
        clearStatsCache(userId);
    }
}