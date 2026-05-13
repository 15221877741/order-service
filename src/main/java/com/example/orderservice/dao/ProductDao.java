package com.example.orderservice.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.orderservice.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProductDao extends BaseMapper<Product> {

    /** 批量更新商品库存 */
    int batchUpdateStock(@Param("list") List<Product> products);
}