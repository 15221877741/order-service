CREATE DATABASE IF NOT EXISTS order_db;
USE order_db;

CREATE TABLE IF NOT EXISTS t_product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    stock INT NOT NULL DEFAULT 0,
    create_time DATETIME,
    update_time DATETIME
);

CREATE TABLE IF NOT EXISTS t_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL,
    status INT NOT NULL DEFAULT 0 COMMENT '0-待处理 1-已完成 2-已取消',
    create_time DATETIME,
    update_time DATETIME,
    INDEX idx_user_id (user_id),
    INDEX idx_order_no (order_no)
);

CREATE TABLE IF NOT EXISTS t_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    product_name VARCHAR(255),
    price DECIMAL(10,2) NOT NULL,
    quantity INT NOT NULL,
    INDEX idx_order_id (order_id)
);

INSERT INTO t_product (name, price, stock, create_time, update_time) VALUES
('iPhone 15', 5999.00, 100, NOW(), NOW()),
('MacBook Pro', 12999.00, 50, NOW(), NOW()),
('AirPods Pro', 1899.00, 200, NOW(), NOW());