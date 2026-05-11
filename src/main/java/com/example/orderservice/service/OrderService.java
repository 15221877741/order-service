package com.example.orderservice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.orderservice.dao.OrderDao;
import com.example.orderservice.dao.OrderItemDao;
import com.example.orderservice.entity.Order;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.Product;
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
    private static final String LIST_CACHE_KEY = "order:list:";
    private static final long LIST_CACHE_TTL = 120;

    private void clearStatsCache(Long userId) {
        redisTemplate.delete(STATS_CACHE_KEY + userId);
    }

    private void clearListCache(Long userId) {
        redisTemplate.delete(LIST_CACHE_KEY + userId);
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
        clearListCache(userId);

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
        batchEnrichOrders(orders);
        return orders;
    }

    public PageInfo<Order> getUserOrders(Long userId, Integer status, int page, int size) {
        log.info("分页查询订单 userId={}, status={}, page={}, size={}", userId, status, page, size);
        String key = LIST_CACHE_KEY + userId;
        @SuppressWarnings("unchecked")
        List<Order> allOrders = (List<Order>) redisTemplate.opsForValue().get(key);
        if (allOrders == null) {
            QueryWrapper<Order> wrapper = new QueryWrapper<>();
            wrapper.eq("user_id", userId).orderByDesc("create_time");
            allOrders = orderDao.selectList(wrapper);
            batchEnrichOrders(allOrders);
            redisTemplate.opsForValue().set(key, allOrders, LIST_CACHE_TTL, TimeUnit.SECONDS);
        }

        List<Order> filtered = allOrders;
        if (status != null) {
            filtered = allOrders.stream().filter(o -> status.equals(o.getStatus())).collect(Collectors.toList());
        }

        int total = filtered.size();
        int totalPages = Math.max((int) Math.ceil((double) total / size), 1);
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<Order> pageList = filtered.subList(fromIndex, toIndex);

        PageInfo<Order> pageInfo = new PageInfo<>(pageList);
        pageInfo.setPageNum(page);
        pageInfo.setPageSize(size);
        pageInfo.setTotal(total);
        pageInfo.setPages(totalPages);
        pageInfo.setIsFirstPage(page == 1);
        pageInfo.setIsLastPage(page >= totalPages);
        pageInfo.setHasNextPage(page < totalPages);
        pageInfo.setHasPreviousPage(page > 1);
        return pageInfo;
    }

    public Map<String, Long> getOrderStats(Long userId) {
        String key = STATS_CACHE_KEY + userId;
        @SuppressWarnings("unchecked")
        Map<String, Long> cached = (Map<String, Long>) redisTemplate.opsForValue().get(key);
        if (cached != null) return cached;

        List<Map<String, Object>> rows = orderDao.selectMaps(
                new QueryWrapper<Order>()
                        .select("COALESCE(SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END), 0) as pending")
                        .select("COALESCE(SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END), 0) as completed")
                        .select("COALESCE(SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END), 0) as cancelled")
                        .select("COUNT(*) as total")
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
            clearListCache(order.getUserId());
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
        clearListCache(userId);
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
        clearListCache(userId);
    }
}