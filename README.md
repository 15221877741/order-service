# 急速订单系统

## 演示地址

<a href="[https://example.com](http://43.133.251.236/)" target="_blank">演示地址</a>

高性能电商订单系统，基于 Spring Boot + Vue 3 构建，支持高并发场景下的毫秒级下单响应。

## 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| 后端框架 | Spring Boot 3.2 + Java 17 | `@SpringBootApplication` |
| 安全 | Spring Security + JWT (jjwt) | 无状态会话，Bearer Token |
| 数据库 | MySQL 8.0 + MyBatis-Plus 3.5 | ORM + 手写 XML Mapper |
| 缓存 | Redis (Lettuce) + StringRedisTemplate | 商品缓存 + Lua 脚本库存 |
| 消息队列 | RabbitMQ 3.1（远程） | 异步订单落库 |
| 前端框架 | Vue 3 + Vite 5 | Composition API |
| 前端 UI | Element Plus 2.14 | 组件库 |
| 状态管理 | Pinia 2.1 | 全局状态 |
| HTTP 客户端 | Axios 1.6 | 请求拦截器 |
| 构建工具 | Maven 3 | 无 Wrapper，需全局 mvn |

## 已实现功能

| 模块 | 功能 |
|------|------|
| 认证 | 登录 / 注册，JWT 生成与验证，401 自动跳转登录 |
| 商品 | 列表展示（Redis 优先）、详情、库存实时显示 |
| 下单 | 异步创建 → 立即返回 orderNo，MQ 异步落库，MQ 失败补偿回滚 Redis 库存 |
| 订单 | 分页查询（PageHelper）、状态更新、删除、批量删除 |
| 订单统计 | 按状态（待处理/已完成/已取消）聚合统计，30s Redis 缓存 |
| 库存同步 | 每 30s 将 Redis 库存批量同步回 MySQL（最终一致） |

## 技术亮点

### 1. Lua 原子库存扣减（无锁竞争）

```
SETNX 锁 → Lua DECRBY + cjson JSON更新
消除 SETNX 互斥，库存操作单次网络往返，吞吐提升数十倍
```

### 2. MQ 异步落库（生产端）

```
createOrder() → Lua扣库存 → 发OrderMessage到MQ → 立即返回
MQ发送失败 → restoreStockAtomic() 补偿回滚 Redis 库存
```

### 3. 批量落库（消费端）

```
BATCH_SIZE=2 或 3秒定时 → 缓冲队列 → 幂等检查 → 一次批量INSERT
XML Mapper: batchInsertOrders + batchInsertOrderItems
```

### 4. Redis 缓存 + 实时库存覆盖

```
product:{id} 缓存商品信息 (TTL 3600s)
product:stock:{id} 缓存实时库存（无TTL）
getProduct() 用 product:stock:{id} 覆盖 stock 字段
listProducts() SCAN product:stock:* 从 Redis 取
```

### 5. 最终一致性（Redis → MySQL 定时同步）

```
StockSyncScheduler 每 30s → selectList全部商品 → CASE WHEN批量更新DB
restoreStockAtomic() 补偿脚本（INCRBY + cjson）
```

## 启动

### 前置依赖

| 组件 | 地址 | 账号 |
|------|------|------|
| MySQL | `localhost:3306/order_db` | root/123456 |
| Redis | `localhost:6379` | 无密码 |
| RabbitMQ | `服务器地址:5672` | admin/admin |

### 启动后端

```bash
cd order-service && mvn spring-boot:run
```

后端运行在 `http://localhost:8080`。

### 启动前端

```bash
cd order-frontend && npm install && npm run dev
```

前端运行在 `http://localhost:3000`。

## API 端点

所有接口统一返回 `{ code, message, data }`。业务异常返回 HTTP 500（`GlobalExceptionHandler`）。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` / `/api/auth/register` | body: `{username, password, nickname?}` |
| GET | `/api/products` | 商品列表（Redis SCAN 优先） |
| GET | `/api/products/{id}` | 商品详情（Redis 缓存优先） |
| POST | `/api/orders` | 创建订单 `{productIds[], quantities[]}`，返回 `{orderNo}` |
| GET | `/api/orders/user/me[?status=&page=&size=]` | 我的订单（PageHelper 分页） |
| GET | `/api/orders/user/me/stats` | 订单统计（缓存 30s） |
| PUT | `/api/orders/{id}/status?status=` | 更新状态 |
| DELETE | `/api/orders/{id}` | 删除订单 |
| POST | `/api/orders/batch-delete` | `{ids[]}` — 仅已取消订单可删 |

## 目录结构

```
order-service/                    # 后端 Spring Boot
├── src/main/java/.../
│   ├── service/                  # ProductService (Lua)、OrderService (MQ)
│   ├── consumer/                 # OrderConsumer (批量落库)
│   ├── scheduler/               # StockSyncScheduler
│   ├── auth/                     # JwtUtil、SecurityConfig、JwtAuthFilter
│   ├── common/                  # GlobalExceptionHandler、Result
│   ├── config/                  # RabbitMQConfig
│   ├── controller/               # Auth、Product、Order
│   ├── dao/                     # OrderDao、ProductDao 等
│   └── entity/                  # Order、Product、OrderMessage、OrderItemMessage
├── src/main/resources/
│   ├── lua/                      # reduceStock.lua、restoreStock.lua
│   ├── mapper/                   # OrderDao.xml、OrderItemDao.xml、ProductDao.xml
│   └── application.yml           # HikariCP (maximum-pool-size=30)

order-frontend/                   # 前端 Vue 3
├── public/                       # 静态资源
├── src/
│   ├── api/                     # Axios 实例、请求拦截器
│   ├── views/                   # Login、ProductList、OrderList
│   ├── router/                  # Vue Router
│   └── stores/                  # Pinia 状态
└── package.json

deploy/
├── docker-compose.yml            # backend (host网络) + frontend (nginx 80)
└── backend/app.jar
```

## 数据库初始化

`init.sql` 位于项目根目录，包含完整建表语句和示例数据：

- 用户: `1/2/123`（username/password）
- 商品: iPhone 15 / MacBook Pro / AirPods Pro / iPhone 20

## 部署

```bash
# 后端打包
cd order-service && mvn package -DskipTests

# 部署
cp target/order-service-*.jar deploy/backend/app.jar
docker-compose -f deploy/docker-compose.yml up -d
```

## 相关文件

| 文件 | 说明 |
|------|------|
| `AGENTS.md` | 开发者指令文件，高价值上下文 |
| `init.sql` | 数据库初始化脚本 |
| `order-stress.jmx` | JMeter 压测配置文件（GUI 创建） |
| `locustfile.py` | Locust Python 压测脚本 |
