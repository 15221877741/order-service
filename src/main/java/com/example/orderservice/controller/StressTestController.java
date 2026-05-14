package com.example.orderservice.controller;

import com.example.orderservice.common.Result;
import com.example.orderservice.entity.StressTestTask;
import com.example.orderservice.service.StressTestService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/stress")
@RequiredArgsConstructor
public class StressTestController {

    private final StressTestService stressTestService;

    private Long getCurrentUserId() {
        return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    @PostMapping("/run")
    public Result<?> run(@RequestBody Map<String, Object> request) {
        int concurrency = ((Number) request.get("concurrency")).intValue();
        Long productId = ((Number) request.get("productId")).longValue();
        int quantity = ((Number) request.get("quantity")).intValue();
        int totalRequests = ((Number) request.get("totalRequests")).intValue();

        if (concurrency < 1 || concurrency > 200) {
            return Result.error("并发数必须在 1~200 之间");
        }
        if (totalRequests < 1 || totalRequests > 100000) {
            return Result.error("总请求数必须在 1~100000 之间");
        }
        if (quantity < 1) {
            return Result.error("购买数量必须大于 0");
        }

        StressTestTask task = new StressTestTask();
        task.setConcurrency(concurrency);
        task.setProductId(productId);
        task.setQuantity(quantity);
        task.setTotalRequests(totalRequests);
        task.setUserId(getCurrentUserId());

        String taskId = stressTestService.submitTask(task);
        return Result.success(Map.of("taskId", taskId));
    }

    @GetMapping("/tasks/{taskId}")
    public Result<?> getTask(@PathVariable String taskId) {
        StressTestTask task = stressTestService.getTask(taskId);
        if (task == null) {
            return Result.error("任务不存在");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", task.getTaskId());
        result.put("status", task.getStatus());
        result.put("progress", task.getProgress());
        result.put("concurrency", task.getConcurrency());
        result.put("productId", task.getProductId());
        result.put("quantity", task.getQuantity());
        result.put("totalRequests", task.getTotalRequests());
        result.put("successCount", task.getSuccessCount().get());
        result.put("failCount", task.getFailCount().get());
        result.put("completedRequests", task.getCompletedRequests());
        result.put("avgResponseTimeMs", String.format("%.1f", task.getAvgResponseTimeMs()));
        result.put("tps", String.format("%.1f", task.getTps()));
        result.put("totalTimeMs", task.getEndTime() > 0 ? task.getEndTime() - task.getStartTime() : 0);
        result.put("errors", task.getErrors());
        return Result.success(result);
    }

    @PostMapping("/reset")
    public Result<?> resetStock() {
        stressTestService.resetStock();
        return Result.success("库存已重置");
    }
}
