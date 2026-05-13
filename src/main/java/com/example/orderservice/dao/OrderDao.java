package com.example.orderservice.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.orderservice.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrderDao extends BaseMapper<Order> {

    /** 批量插入订单，回写自增 id */
    int batchInsertOrders(@Param("list") List<Order> orders);

    /** 查询用户订单（PageHelper 分页） */
    List<Order> selectUserOrders(@Param("userId") Long userId, @Param("status") Integer status);
}