package com.example.orderservice.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.orderservice.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderItemDao extends BaseMapper<OrderItem> {
}