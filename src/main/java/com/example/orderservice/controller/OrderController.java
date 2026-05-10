package com.example.orderservice.controller;

import com.example.orderservice.common.Result;
import com.example.orderservice.entity.Order;
import com.example.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;

    private Long getCurrentUserId() {
        return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    @PostMapping
    public Result<?> create(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<?> productIds = (List<?>) request.get("productIds");
        @SuppressWarnings("unchecked")
        List<?> quantities = (List<?>) request.get("quantities");
        Order order = orderService.createOrder(getCurrentUserId(), productIds, quantities);
        return Result.success(order);
    }

    @GetMapping("/{id}")
    public Result<?> get(@PathVariable Long id) {
        return Result.success(orderService.getOrder(id));
    }

    @GetMapping("/user/me")
    public Result<?> getMyOrders() {
        return Result.success(orderService.getUserOrders(getCurrentUserId()));
    }

    @PutMapping("/{id}/status")
    public Result<?> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        orderService.updateOrderStatus(id, status);
        return Result.success("状态更新成功");
    }

    @DeleteMapping("/{id}")
    public Result<?> deleteOrder(@PathVariable Long id) {
        orderService.deleteOrder(id, getCurrentUserId());
        return Result.success("删除成功");
    }

    @PostMapping("/batch-delete")
    public Result<?> batchDeleteOrders(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<?> ids = (List<?>) request.get("ids");
        orderService.batchDeleteOrders(ids, getCurrentUserId());
        return Result.success("批量删除成功");
    }
}
