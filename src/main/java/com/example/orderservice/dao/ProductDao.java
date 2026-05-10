package com.example.orderservice.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.orderservice.entity.Product;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductDao extends BaseMapper<Product> {
}