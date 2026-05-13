package com.example.orderservice.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * MQ 消息体：订单创建消息，由 OrderService 发送给 OrderConsumer 异步落库
 */
@Data
public class OrderMessage {
    private String orderNo;
    private Long userId;
    private BigDecimal totalAmount;
    private Integer status;
    private LocalDateTime createTime;
    private List<OrderItemMessage> items;
}
