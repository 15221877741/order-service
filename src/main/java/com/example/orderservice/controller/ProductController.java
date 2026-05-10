package com.example.orderservice.controller;

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
    public List<Product> list() {

        return productService.listProducts();
    }

    @GetMapping("/{id}")
    public Product get(@PathVariable Long id) {
        return productService.getProduct(id);
    }

    @PostMapping
    public Product create(@RequestBody Product product) {
        productService.listProducts();
        return product;
    }
}