# 智享生活 · AI 导购服务平台

一个面向本地生活场景的智能导购与点评服务系统。

该项目扩展了高并发秒杀、Redis 缓存治理、达人探店、关注关系、AI 店铺问答、平台级智能推荐和 RAG 知识库能力。

当前版本已经具备：

- Redis Token 登录与会话续期
- 商铺、分类、博客、点赞、关注等本地生活业务
- 缓存穿透、缓存击穿、逻辑过期、互斥锁缓存重建
- Lua + Redis Stream 秒杀下单
- Redisson 分布式锁控制一人一单
- 单店 AI 问答页面
- 平台级 AI 导购助手
- 店铺知识库构建与重建
- 店铺画像、文档切片、向量化与语义检索
- JSON / SQL / Markdown 配套文档

## 1. 项目定位

`智享生活 · AI 导购服务平台` 将把本地生活点评系统升级为一个更完整、更工程化、适合演示和继续扩展的 AI 导购服务。

核心设计目标：

- 把传统点评、探店、优惠券秒杀业务整合为可运行的后端服务
- 使用 Redis 解决登录态、缓存、高并发扣减和异步订单处理问题
- 引入 AI 能力，让用户可以围绕商铺、价格、环境、优惠券进行自然语言咨询
- 构建 RAG 知识库，让 AI 回答基于结构化店铺数据和业务数据，而不是凭空生成
- 提供静态页面、HTTP 示例和后端接口，便于本地演示和接口调试

## 2. 系统架构

项目采用 Spring Boot 单体后端架构，核心链路如下：

```text
前端页面 / HTTP Client
        |
Controller 接口层
        |
Service 业务层
        |
MyBatis-Plus 数据访问层
        |
MySQL 持久化数据

Redis：
- 登录 Token
- 验证码
- 热点缓存
- 分布式锁
- 秒杀库存
- Stream 异步订单队列
- 向量数据存储

AI 服务：
- Chat Completion
- Embedding
- RAG 检索增强问答
```

## 3. 核心功能

### 3.1 用户与登录

- 手机号验证码登录
- Redis 保存登录 Token
- 拦截器自动刷新 Token 有效期
- ThreadLocal 保存当前登录用户

### 3.2 商铺与探店

- 商铺分类查询
- 商铺详情查询
- 商铺缓存查询
- 探店博客发布与查询
- 点赞、取消点赞
- 关注用户与共同关注
- 滚动分页查询关注动态

### 3.3 Redis 缓存治理

- 空值缓存防止缓存穿透
- 互斥锁防止缓存击穿
- 逻辑过期降低热点 Key 重建风险
- Redis 缓存封装工具类
- 商铺数据缓存与主动更新

### 3.4 优惠券秒杀

- Lua 脚本原子判断库存和一人一单
- Redis Stream 写入订单消息
- 后台线程异步消费订单
- Redisson 分布式锁兜底控制并发
- MySQL 持久化秒杀订单

### 3.5 AI 导购助手

- 单店 AI 问答
- 平台级多店推荐
- 根据用户问题筛选商铺、优惠券和评论信息
- 支持会话状态与上下文追问
- 支持静态 HTML 页面演示

### 3.6 RAG 知识库

- 店铺画像构建
- 店铺知识文档生成
- 文档切片
- Embedding 向量化
- Redis 向量存储
- 语义检索
- 知识库重建接口

## 4. 技术栈

- Java 8
- Spring Boot 2.3.12
- MyBatis-Plus
- MySQL
- Redis
- Redisson
- Lua
- Redis Stream
- Hutool
- Lombok
- DashScope / OpenAI Compatible API
- HTML / JavaScript

## 5. 项目结构

```text
src/main/java/com/hmdp
├── config          # Web、MyBatis、Redisson、AI RAG 配置
├── controller      # 用户、商铺、博客、优惠券、AI 接口
├── dto             # 请求与响应对象
├── entity          # 数据库实体
├── mapper          # MyBatis-Plus Mapper
├── service         # 业务接口
├── service/impl    # 业务实现
└── utils           # Redis、锁、登录、缓存等工具类

src/main/resources
├── application.yaml      # 公共配置，使用环境变量注入敏感信息
├── db                    # 初始化 SQL 与 RAG 扩展 SQL
├── mapper                # MyBatis XML
├── seckill.lua           # 秒杀 Lua 脚本
├── unlock.lua            # 解锁 Lua 脚本
└── static                # AI 问答演示页面
```

## 6. 本地运行

### 6.1 环境要求

- JDK 8+
- Maven 3.x
- MySQL 5.7+
- Redis 6.x+

### 6.2 初始化数据库

按需执行以下 SQL：

- `src/main/resources/db/hmdp.sql`
- `src/main/resources/db/hmdp_rag.sql`
- `src/main/resources/db/hmdp_rag_v2.sql`
- `src/main/resources/db/hmdp_rag_v3.sql`

### 6.3 配置环境变量

复制示例配置：

```bash
cp .env.example .env
```

按本地环境填写：

```text
MYSQL_URL=jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC
MYSQL_USERNAME=root
MYSQL_PASSWORD=
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=
AI_API_KEY=
AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
AI_MODEL=qwen-plus
EMBEDDING_API_KEY=
EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings
EMBEDDING_MODEL=text-embedding-v3
```

说明：`.env` 只用于本地运行，不应提交到 GitHub。

### 6.4 启动服务

```bash
mvn spring-boot:run
```

默认端口：

```text
http://localhost:8081
```

## 7. 页面与接口

可直接访问的静态页面：

- `/ai-shop-chat.html`
- `/ai-assistant.html`
- `/ai-platform-chat.html`

常用接口示例：

- `POST /user/code`：发送验证码
- `POST /user/login`：用户登录
- `GET /shop/{id}`：查询商铺详情
- `POST /voucher-order/seckill/{id}`：秒杀下单
- `POST /ai/shop/chat`：单店 AI 问答
- `POST /ai/platform/chat`：平台 AI 导购
- `POST /ai/platform/search`：语义检索
- `POST /ai/admin/rag/rebuild-and-index/all`：重建知识库

## 8. 安全与隐私

本项目适合公开到 GitHub，但需要遵守以下约束：

- 不提交 `.env`
- 不提交真实 API Key
- 不提交真实数据库密码
- 不提交真实 Redis 密码
- 不提交个人脚本、IDE 配置、日志和构建产物
- `application.yaml` 中只保留环境变量占位
- `.env.example` 只保留空值或示例值

当前推荐做法：

- 公开配置放在 `application.yaml`
- 本地私密配置放在 `.env` 或系统环境变量
- README 只描述配置项，不暴露真实值

## 9. 后续可扩展方向

- 接入 Docker Compose，一键启动 MySQL、Redis 和后端服务
- 增加 Swagger / OpenAPI 文档
- 增加 AI 回答引用来源展示
- 增加 RAG 重建任务进度查询
- 增加接口鉴权与后台管理权限
- 增加单元测试和压测脚本
