package com.example.orderservice.controller;

import com.example.orderservice.common.Result;
import com.example.orderservice.entity.Product;
import com.example.orderservice.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {
    private final ProductService productService;

    @GetMapping
    public Result<?> list() {
        return Result.success(productService.listProducts());
    }

    @GetMapping("/{id}")
    public Result<?> get(@PathVariable Long id) {
        return Result.success(productService.getProduct(id));
    }

    @PostMapping
    public Result<?> create(@RequestBody Product product) {
        return Result.success(product);
    }
}