package com.example.orderservice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.orderservice.dao.OrderDao;
import com.example.orderservice.dao.OrderItemDao;
import com.example.orderservice.entity.Order;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.Product;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private static final String ORDER_QUEUE = "order.create";
    private static final String STATS_CACHE_KEY = "order:stats:";

    private void clearStatsCache(Long userId) {
        redisTemplate.delete(STATS_CACHE_KEY + userId);
    }

    @Transactional
    public Order createOrder(Long userId, List<?> productIds, List<?> quantities) {
        String submitKey = "order:submit:" + userId + ":" + productIds.hashCode();
        Boolean submitted = redisTemplate.opsForValue().setIfAbsent(submitKey, "1", 3, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(submitted)) {
            throw new RuntimeException("请勿重复提交");
        }
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (int i = 0; i < productIds.size(); i++) {
            Long productId = ((Number) productIds.get(i)).longValue();
            Integer quantity = ((Number) quantities.get(i)).intValue();
            Product product = productService.getProduct(productId);
            if (product == null) {
                throw new RuntimeException("Product not found: " + productId);
            }
            if (product.getStock() < quantity) {
                throw new RuntimeException("Insufficient stock for product: " + product.getName());
            }
            boolean stockReduced = productService.reduceStock(productId, quantity);
            if (!stockReduced) {
                throw new RuntimeException("Failed to reduce stock for product: " + product.getName());
            }
            totalAmount = totalAmount.add(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
        }

        Order order = new Order();
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(userId);
        order.setTotalAmount(totalAmount);
        order.setStatus(0);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        orderDao.insert(order);

        for (int i = 0; i < productIds.size(); i++) {
            Long productId = ((Number) productIds.get(i)).longValue();
            Integer quantity = ((Number) quantities.get(i)).intValue();
            Product product = productService.getProduct(productId);
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(productId);
            item.setProductName(product.getName());
            item.setPrice(product.getPrice());
            item.setQuantity(quantity);
            orderItemDao.insert(item);
        }

        rabbitTemplate.convertAndSend(ORDER_QUEUE, order);
        clearStatsCache(userId);

        return order;
    }

    public Order getOrder(Long orderId) {
        return orderDao.selectById(orderId);
    }

    public List<Order> getUserOrders(Long userId) {
        QueryWrapper<Order> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        wrapper.orderByDesc("create_time");
        List<Order> orders = orderDao.selectList(wrapper);
        for (Order order : orders) {
            enrichOrderItems(order);
        }
        return orders;
    }

    public PageInfo<Order> getUserOrders(Long userId, Integer status, int page, int size) {
        log.info("分页查询订单 userId={}, status={}, page={}, size={}", userId, status, page, size);
        PageHelper.startPage(page, size);
        QueryWrapper<Order> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("create_time");
        List<Order> orders = orderDao.selectList(wrapper);
        for (Order order : orders) {
            enrichOrderItems(order);
        }
        PageInfo<Order> pageInfo = new PageInfo<>(orders);
        return pageInfo;
    }

    public Map<String, Long> getOrderStats(Long userId) {
        String key = STATS_CACHE_KEY + userId;
        @SuppressWarnings("unchecked")
        Map<String, Long> cached = (Map<String, Long>) redisTemplate.opsForValue().get(key);
        if (cached != null) return cached;

        long total = orderDao.selectCount(new QueryWrapper<Order>().eq("user_id", userId));
        long pending = orderDao.selectCount(new QueryWrapper<Order>().eq("user_id", userId).eq("status", 0));
        long completed = orderDao.selectCount(new QueryWrapper<Order>().eq("user_id", userId).eq("status", 1));
        long cancelled = orderDao.selectCount(new QueryWrapper<Order>().eq("user_id", userId).eq("status", 2));
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