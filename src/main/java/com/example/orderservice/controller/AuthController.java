package com.example.orderservice.controller;

import com.example.orderservice.auth.JwtUtil;
import com.example.orderservice.common.Result;
import com.example.orderservice.entity.User;
import com.example.orderservice.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final UserService userService;
    private final JwtUtil jwtUtil;

    @PostMapping("/register")
    public Result<?> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String nickname = body.get("nickname");
        if (username == null || password == null) {
            return Result.error("用户名和密码不能为空");
        }
        try {
            User user = userService.register(username, password, nickname);
            String token = jwtUtil.generateToken(user.getId(), user.getUsername());
            return Result.success(Map.of(
                "token", token,
                "userId", user.getId(),
                "username", user.getUsername(),
                "nickname", user.getNickname()
            ));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/login")
    public Result<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) {
            return Result.error("用户名和密码不能为空");
        }
        try {
            User user = userService.login(username, password);
            String token = jwtUtil.generateToken(user.getId(), user.getUsername());
            return Result.success(Map.of(
                "token", token,
                "userId", user.getId(),
                "username", user.getUsername(),
                "nickname", user.getNickname()
            ));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}
