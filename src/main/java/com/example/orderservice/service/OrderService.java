package com.example.orderservice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.orderservice.dao.OrderDao;
import com.example.orderservice.dao.OrderItemDao;
import com.example.orderservice.entity.Order;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderDao orderDao;
    private final OrderItemDao orderItemDao;
    private final ProductService productService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RabbitTemplate rabbitTemplate;

    private static final String ORDER_QUEUE = "order.create";

    @Transactional
    public Order createOrder(Long userId, List<?> productIds, List<?> quantities) {
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
            List<OrderItem> items = orderItemDao.selectList(
                    new QueryWrapper<OrderItem>().eq("order_id", order.getId()));
            order.setProductNames(items.stream().map(OrderItem::getProductName).collect(Collectors.joining(", ")));
            order.setTotalQuantity(items.stream().mapToInt(OrderItem::getQuantity).sum());
        }
        return orders;
    }

    @Transactional
    public void updateOrderStatus(Long orderId, Integer status) {
        Order order = orderDao.selectById(orderId);
        if (order != null) {
            order.setStatus(status);
            order.setUpdateTime(LocalDateTime.now());
            orderDao.updateById(order);
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
    }
}