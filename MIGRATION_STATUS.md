# Migration Status

> 黑马点评 → 高校实验资源预约平台 分阶段重构进度

## Current Phase

Phase 0 — Baseline（已完成）

## Passed

- Phase 0 — Baseline ✅

## Current Work

- [x] JDK 8 compile baseline（BUILD SUCCESS，72 文件）
- [x] 数据库 schema 基线（10 张表）
- [x] Redis Key 基线（15 个 Key Pattern，见 §4）
- [x] RabbitMQ 拓扑基线（Exchange/Queue/DLX/DLQ）
- [x] 文档第 24 节 10 个问题逐条核验（全部属实）
- [x] Git checkpoint（tag `before-lab-reservation-refactor`）

## Pending

- Phase 1 Legacy Cleanup
- Phase 2 Laboratory & Equipment
- Phase 3 User & Authorization
- Phase 4 Equipment Cache
- Phase 5 Reservation Batch
- Phase 6 Redis + Lua Reservation
- Phase 7 RabbitMQ Reservation
- Phase 8 Consistency & Reliability
- Phase 9 Cancellation & Fulfillment
- Phase 10 Final Cleanup

## Known Issues

### P0（阻塞运行时验证，需用户提供）

1. 本机 MySQL 凭据未知（`root/123`、空密码均 Access denied）
2. Redis 配置指向不可达的 `192.168.150.101:6379`；本机 6379 需认证且 `123321` 不正确
3. RabbitMQ 管理 API 无法用 `guest/guest` 登录（AMQP 5672 可达）

### P1（业务/一致性缺陷，后续 Phase 处理）

1. 订单表无任何唯一索引 → Phase 8
2. 消费端扣库存 + INSERT 无事务 → Phase 8
3. Confirm Timeout 立即回滚 Redis → Phase 8
4. 补偿 Lua 不幂等 → Phase 8
5. Lua 不校验预约时间窗口 → Phase 5 / 6
6. DLQ 无治理入口 → Phase 8
7. logout 未实现 → Phase 3
8. MQ 直接发送数据库 Entity → Phase 7

### P2（清理项）

1. Redis Key 重复定义（Java 常量 vs Lua 字面量）
2. `seckill:stock:` / `seckill:order:` 无 TTL
3. 四套历史秒杀实现以注释形式残留
4. `SystemConstants.IMAGE_UPLOAD_DIR` 硬编码 Windows 路径
5. 明文密码

## Last Review

Phase 0 baseline recorded — 2026-10-03，详见 `docs/refactor/PHASE0-BASELINE.md`
