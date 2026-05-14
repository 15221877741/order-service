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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RabbitMQ 订单消息消费者
 * <p>
 * 采用批量消费 + 并发多线程消费双重模式：
 * <ul>
 *   <li>批量消费：每批最多 ${batch-size} 条，${batch-timeout}毫秒 超时兜底，由 RabbitMQ 批量投递给 handleOrderBatch</li>
 *   <li>并发消费：3~5 个消费者线程并发处理，提升吞吐量</li>
 * </ul>
 * 消费者方法直接接收 List&lt;OrderMessage&gt;，由 {@link #doBatchInsert(List)} 完成批量落库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderConsumer {

    private final OrderDao orderDao;
    private final OrderItemDao orderItemDao;

    /**
     * 批量消费入口
     * <p>
     * 由 Spring AMQP 根据 batch-size 积累消息后批量投递，
     * 这里直接转发给 doBatchInsert 进行批量落库。
     *
     * @param msgs 一个批次的订单消息（最多 100 条，超时未满也会投递）
     */
    @RabbitListener(queues = RabbitMQConfig.ORDER_QUEUE,
                    containerFactory = "rabbitListenerContainerFactory")
    public void handleOrderBatch(List<OrderMessage> msgs) {
        if (msgs == null || msgs.isEmpty()) return;
        doBatchInsert(msgs);
    }

    /**
     * 批量落库：在一个事务内完成订单 + 商品明细的批量插入
     * <p>
     * 处理流程：
     * <ol>
     *   <li>幂等检查：根据 orderNo 查出已存在的订单，过滤重复消息</li>
     *   <li>批量插入订单：一次性 SQL 批量插入，useGeneratedKeys 回写自增 id</li>
     *   <li>组装商品明细：用回写的 orderId 关联对应商品记录</li>
     *   <li>批量插入商品明细：一次 SQL 批量插入全部商品记录</li>
     * </ol>
     *
     * @param batch 批次消息列表
     */
    @Transactional
    public void doBatchInsert(List<OrderMessage> batch) {
        // 1. 收集所有 orderNo，幂等检查
        List<String> orderNos = batch.stream()
                .map(OrderMessage::getOrderNo)
                .collect(Collectors.toList());
        List<Order> existing = orderDao.selectList(
                new QueryWrapper<Order>().in("order_no", orderNos));
        Set<String> existingNos = existing.stream()
                .map(Order::getOrderNo)
                .collect(Collectors.toSet());

        // 2. 过滤重复消息，构建待插入订单列表
        List<Order> newOrders = new ArrayList<>();
        Map<String, List<OrderItemMessage>> pendingItems = new LinkedHashMap<>();

        for (OrderMessage msg : batch) {
            // 已存在的订单直接跳过，防止重复落库
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
            // 暂存商品明细，按 orderNo 索引，便于后续与 orderId 关联
            if (msg.getItems() != null) {
                pendingItems.put(msg.getOrderNo(), msg.getItems());
            }
        }

        // 无新订单时直接返回
        if (newOrders.isEmpty()) return;

        // 3. 批量插入订单，useGeneratedKeys 自动回写自增 id
        orderDao.batchInsertOrders(newOrders);

        // 4. 用回写的 orderId 组装商品明细
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

        // 5. 批量插入商品明细
        if (!allItems.isEmpty()) {
            orderItemDao.batchInsertOrderItems(allItems);
        }
        log.info("批量落库完成：{} 条订单，{} 条商品明细", newOrders.size(), allItems.size());
    }
}