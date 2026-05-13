package com.example.orderservice.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.orderservice.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrderItemDao extends BaseMapper<OrderItem> {

    /** 批量插入订单商品明细 */
    int batchInsertOrderItems(@Param("list") List<OrderItem> items);
}