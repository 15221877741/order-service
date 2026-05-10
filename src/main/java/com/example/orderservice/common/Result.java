package com.example.orderservice.common;

import lombok.Data;

@Data
public class Result<T> {
    private int code;
    private String message;
    private T data;

    public static Result<?> success() {
        return success(null);
    }

    public static Result<?> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("success");
        result.setData(data);
        return result;
    }

    public static Result<?> error(String message) {
        return error(500, message);
    }

    public static Result<?> error(int code, String message) {
        Result<?> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}