package com.example.orderservice.config;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    public static final String ORDER_QUEUE = "order.create";
    public static final String ORDER_EXCHANGE = "order.exchange";

    @Bean
    public Queue orderQueue() {
        return new Queue(ORDER_QUEUE, true);
    }
}