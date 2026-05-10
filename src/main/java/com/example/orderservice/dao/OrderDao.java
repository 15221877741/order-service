package com.example.orderservice.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.orderservice.entity.Order;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderDao extends BaseMapper<Order> {
}