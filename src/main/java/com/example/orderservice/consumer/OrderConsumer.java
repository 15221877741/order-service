package com.example.orderservice.consumer;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.orderservice.config.RabbitMQConfig;
import com.example.orderservice.dao.OrderDao;
import com.example.orderservice.dao.OrderItemDao;
import com.example.orderservice.entity.Order;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderConsumer {

    private final OrderDao orderDao;
    private final OrderItemDao orderItemDao;

    private static final int BATCH_SIZE = 2;

    private final List<OrderMessage> buffer = new ArrayList<>();
    private final Object lock = new Object();

    @RabbitListener(queues = RabbitMQConfig.ORDER_QUEUE)
    public void handleOrderCreated(OrderMessage msg) {
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
    @Scheduled(fixedRate = 3000)
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

        List<OrderItem> allItems = new ArrayList<>();

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
            orderDao.insert(order);

            if (msg.getItems() != null) {
                for (var itemMsg : msg.getItems()) {
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

        // 批量插入所有商品明细（循环插入，同类事务内提交）
        for (OrderItem item : allItems) {
            orderItemDao.insert(item);
        }

        log.info("批量落库完成：{} 条订单，{} 条商品明细",
                batch.size() - existingNos.size(), allItems.size());
    }
}
