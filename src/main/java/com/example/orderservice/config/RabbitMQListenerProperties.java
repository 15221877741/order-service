package com.example.orderservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RabbitMQ Listener 配置属性类
 * <p>
 * 绑定 application.yml 中的 spring.rabbitmq.listener.simple.* 配置项，
 * 供 {@link RabbitMQConfig#rabbitListenerContainerFactory} 动态读取使用。
 */
@Data
@ConfigurationProperties(prefix = "spring.rabbitmq.listener.simple")
public class RabbitMQListenerProperties {
    /**
     * 每批积累的消息数量，达到该数量后一次性投递给消费者
     */
    private int batchSize;

    /**
     * 批量消费超时兜底时间（毫秒）
     * <p>
     * 即使未达到 batchSize，到达该超时时间也会触发投递，
     * 防止最后不足 batchSize 的消息长期不落库。
     */
    private long batchTimeout;

    /**
     * 是否启用批量消费模式
     * <p>
     * 必须为 true 才能使 batchSize 生效。
     */
    private boolean consumerBatchEnabled;

    /**
     * 初始并发消费者数量
     */
    private int concurrency;

    /**
     * 并发消费者上限数量
     * <p>
     * 负载上升时可自动扩容到的最大消费者线程数。
     */
    private int maxConcurrency;
}