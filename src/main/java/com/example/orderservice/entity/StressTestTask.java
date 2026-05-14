package com.example.orderservice.entity;

import lombok.Data;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Data
public class StressTestTask {
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_COMPLETED = 2;
    public static final int STATUS_FAILED = 3;

    private String taskId;
    private int status;
    private int concurrency;
    private Long productId;
    private int quantity;
    private int totalRequests;
    private Long userId;
    private long startTime;
    private long endTime;

    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private final AtomicLong totalResponseTimeMs = new AtomicLong(0);
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private static final int MAX_ERRORS = 50;

    public void addSuccess(long responseTimeMs) {
        successCount.incrementAndGet();
        totalResponseTimeMs.addAndGet(responseTimeMs);
    }

    public void addError(String error) {
        failCount.incrementAndGet();
        if (errors.size() < MAX_ERRORS) {
            errors.add(error);
        }
    }

    public int getProgress() {
        int total = successCount.get() + failCount.get();
        return totalRequests > 0 ? (int) ((double) total / totalRequests * 100) : 0;
    }

    public double getAvgResponseTimeMs() {
        int total = successCount.get() + failCount.get();
        return total > 0 ? (double) totalResponseTimeMs.get() / total : 0;
    }

    public double getTps() {
        long elapsed = endTime - startTime;
        return elapsed > 0 ? (double) getCompletedRequests() / elapsed * 1000 : 0;
    }

    public int getCompletedRequests() {
        return successCount.get() + failCount.get();
    }
}
