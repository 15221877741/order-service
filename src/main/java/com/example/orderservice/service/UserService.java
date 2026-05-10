package com.example.orderservice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.orderservice.dao.UserDao;
import com.example.orderservice.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserDao userDao;
    private final PasswordEncoder passwordEncoder;

    public User register(String username, String password, String nickname) {
        if (userDao.selectOne(new QueryWrapper<User>().eq("username", username)) != null) {
            throw new RuntimeException("用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname != null ? nickname : username);
        user.setCreateTime(LocalDateTime.now());
        userDao.insert(user);
        return user;
    }

    public User login(String username, String password) {
        User user = userDao.selectOne(new QueryWrapper<User>().eq("username", username));
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("密码错误");
        }
        return user;
    }

    public User getById(Long id) {
        return userDao.selectById(id);
    }
}
