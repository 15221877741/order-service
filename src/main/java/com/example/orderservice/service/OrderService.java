package com.example.orderservice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.orderservice.dao.OrderDao;
import com.example.orderservice.dao.OrderItemDao;
import com.example.orderservice.entity.Order;
import com.example.orderservice.entity.OrderItem;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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
    public Order createOrder(Long userId, List<Long> productIds, List<Integer> quantities) {
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (int i = 0; i < productIds.size(); i++) {
            var product = productService.getProduct(productIds.get(i));
            if (product == null) {
                throw new RuntimeException("Product not found: " + productIds.get(i));
            }
            if (product.getStock() < quantities.get(i)) {
                throw new RuntimeException("Insufficient stock for product: " + product.getName());
            }
            boolean stockReduced = productService.reduceStock(productIds.get(i), quantities.get(i));
            if (!stockReduced) {
                throw new RuntimeException("Failed to reduce stock for product: " + product.getName());
            }
            totalAmount = totalAmount.add(product.getPrice().multiply(BigDecimal.valueOf(quantities.get(i))));
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
            var product = productService.getProduct(productIds.get(i));
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(productIds.get(i));
            item.setProductName(product.getName());
            item.setPrice(product.getPrice());
            item.setQuantity(quantities.get(i));
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
        return orderDao.selectList(wrapper);
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
}