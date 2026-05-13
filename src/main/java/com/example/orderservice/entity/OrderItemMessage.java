package com.example.orderservice.entity;

import lombok.Data;
import java.math.BigDecimal;

/**
 * MQ 消息中的商品明细
 */
@Data
public class OrderItemMessage {
    private Long productId;
    private String productName;
    private BigDecimal price;
    private Integer quantity;
}
