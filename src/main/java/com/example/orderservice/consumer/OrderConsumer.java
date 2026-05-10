package com.example.orderservice.consumer;

import com.example.orderservice.config.RabbitMQConfig;
import com.example.orderservice.entity.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OrderConsumer {

    @RabbitListener(queues = RabbitMQConfig.ORDER_QUEUE)
    public void handleOrderCreated(Order order) {
        log.info("Received order notification: orderNo={}, totalAmount={}, userId={}",
                order.getOrderNo(), order.getTotalAmount(), order.getUserId());
        log.info("Order processing completed for order: {}", order.getOrderNo());
    }
}