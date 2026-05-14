package com.example.orderservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置类
 * <p>
 * 批量消费 + 并发消费双重配置：
 * <ul>
 *   <li>batch-size: 每批积累 N 条消息后投递给消费者</li>
 *   <li>batch-timeout: 超时兜底，防止最后不足 batchSize 条长期不落库</li>
 *   <li>consumer-batch-enabled: 开启批量消费模式</li>
 *   <li>concurrency: 初始并发消费者数</li>
 *   <li>max-concurrency: 并发上限，负载上升时自动扩容</li>
 * </ul>
 * 配置值从 application.yml 中读取，绑定到 {@link RabbitMQListenerProperties}。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RabbitMQListenerProperties.class)
public class RabbitMQConfig {
    public static final String ORDER_QUEUE = "order.create";
    public static final String ORDER_EXCHANGE = "order.exchange";

    @Bean
    public Queue orderQueue() {
        return new Queue(ORDER_QUEUE, true);
    }

    @Bean
    public MessageConverter messageConverter() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        return template;
    }

/**
     * 批量 + 并发 listener 容器工厂
     * <p>
     * 配合 @RabbitListener(containerFactory = "rabbitListenerContainerFactory") 使用，
     * 使消费者方法接收 List<T> 而非单条消息，实现批量落库。
     * <p>
     * 配置值从 {@link RabbitMQListenerProperties} 动态读取，而非硬编码。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            RabbitMQListenerProperties props) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        // 从配置类读取，而非硬编码
        factory.setBatchSize(props.getBatchSize());
        factory.setConsumerBatchEnabled(props.isConsumerBatchEnabled());
        factory.setConcurrentConsumers(props.getConcurrency());
        factory.setMaxConcurrentConsumers(props.getMaxConcurrency());
        return factory;
    }
}