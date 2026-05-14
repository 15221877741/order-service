package com.example.orderservice.consumer;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.orderservice.config.RabbitMQConfig;
import com.example.orderservice.dao.OrderDao;
import com.example.orderservice.dao.OrderItemDao;
import com.example.orderservice.entity.Order;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderItemMessage;
import com.example.orderservice.entity.OrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderConsumer {

    private final OrderDao orderDao;
    private final OrderItemDao orderItemDao;

    private static final int BATCH_SIZE = 100;

    private final List<OrderMessage> buffer = new ArrayList<>();
    private final Object lock = new Object();

    @RabbitListener(queues = RabbitMQConfig.ORDER_QUEUE)
    public void handleOrderCreated(OrderMessage msg) {
        log.info(">>>rabbitmq消费消息：{}",msg);
        // 加入缓冲区，满 BATCH_SIZE 条后触发批量落库
        boolean shouldFlush;
        synchronized (lock) {
            buffer.add(msg);
            shouldFlush = buffer.size() >= BATCH_SIZE;
        }
        if (shouldFlush) {
            flushBatch();
        }
    }

    // 定时兜底：防止最后不足 BATCH_SIZE 条时永远不刷
    @Scheduled(fixedRate = 6000)
    public void flushPeriodically() {
        flushBatch();
    }

    /**
     * 批量落库：一次性提交所有订单 + 商品明细
     */
    public synchronized void flushBatch() {
        List<OrderMessage> batch;
        synchronized (lock) {
            if (buffer.isEmpty()) return;
            batch = new ArrayList<>(buffer);
            buffer.clear();
        }
        doBatchInsert(batch);
    }

    @Transactional
    public void doBatchInsert(List<OrderMessage> batch) {
        // 幂等检查：一次查出所有 orderNo，过滤已存在的
        List<String> orderNos = batch.stream()
                .map(OrderMessage::getOrderNo)
                .collect(Collectors.toList());
        List<Order> existing = orderDao.selectList(
                new QueryWrapper<Order>().in("order_no", orderNos));
        Set<String> existingNos = existing.stream()
                .map(Order::getOrderNo)
                .collect(Collectors.toSet());

        // 收集新订单 + 按 orderNo 暂存待插入的商品
        List<Order> newOrders = new ArrayList<>();
        Map<String, List<OrderItemMessage>> pendingItems = new LinkedHashMap<>();

        for (OrderMessage msg : batch) {
            if (existingNos.contains(msg.getOrderNo())) {
                log.warn("跳过重复消息 orderNo={}", msg.getOrderNo());
                continue;
            }
            Order order = new Order();
            order.setOrderNo(msg.getOrderNo());
            order.setUserId(msg.getUserId());
            order.setTotalAmount(msg.getTotalAmount());
            order.setStatus(msg.getStatus());
            order.setCreateTime(msg.getCreateTime());
            order.setUpdateTime(LocalDateTime.now());
            newOrders.add(order);
            if (msg.getItems() != null) {
                pendingItems.put(msg.getOrderNo(), msg.getItems());
            }
        }

        if (newOrders.isEmpty()) return;

        // 一次 SQL 批量插入订单，useGeneratedKeys 自动回写 id
        orderDao.batchInsertOrders(newOrders);

        // 此时每个 order 已有 id，按 orderNo 匹配组装 OrderItem
        List<OrderItem> allItems = new ArrayList<>();
        for (Order order : newOrders) {
            var itemMsgs = pendingItems.get(order.getOrderNo());
            if (itemMsgs != null) {
                for (var itemMsg : itemMsgs) {
                    OrderItem item = new OrderItem();
                    item.setOrderId(order.getId());
                    item.setProductId(itemMsg.getProductId());
                    item.setProductName(itemMsg.getProductName());
                    item.setPrice(itemMsg.getPrice());
                    item.setQuantity(itemMsg.getQuantity());
                    allItems.add(item);
                }
            }
        }
        // 一次 SQL 批量插入所有商品明细
        if (!allItems.isEmpty()) {
            orderItemDao.batchInsertOrderItems(allItems);
        }
        log.info("批量落库完成：{} 条订单，{} 条商品明细", newOrders.size(), allItems.size());
    }
}
