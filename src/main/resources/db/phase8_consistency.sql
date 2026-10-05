CREATE TABLE IF NOT EXISTS `tb_reservation_request` (
  `request_id`  bigint unsigned NOT NULL COMMENT '请求id（RedisIdWorker，与预约id同源）',
  `user_id`     bigint unsigned NOT NULL COMMENT '用户id',
  `batch_id`    bigint unsigned NOT NULL COMMENT '预约批次id',
  `status`      varchar(16) NOT NULL COMMENT 'PRE_ACCEPTED/PUBLISHED/CONFIRMED/UNKNOWN/COMPENSATED',
  `fail_reason` varchar(255) DEFAULT NULL COMMENT '失败原因（DLQ 治理用）',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`request_id`) USING BTREE,
  KEY `idx_status_update_time` (`status`, `update_time`) USING BTREE,
  KEY `idx_user_batch` (`user_id`, `batch_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='预约请求状态账本';

CREATE TABLE IF NOT EXISTS `tb_reservation_dlq` (
  `id`             bigint unsigned NOT NULL AUTO_INCREMENT,
  `reservation_id` bigint unsigned DEFAULT NULL COMMENT '解析出的预约id，解析失败为 NULL',
  `payload`        text COMMENT '死信消息体原文',
  `failure_reason` varchar(512) DEFAULT NULL COMMENT '失败原因（账本 JOIN 或 x-death）',
  `replay_count`   int NOT NULL DEFAULT 0 COMMENT '已重放次数',
  `status`         varchar(16) NOT NULL DEFAULT 'NEW' COMMENT 'NEW/REPLAYED/DISCARDED',
  `create_time`    timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`    timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_reservation_id` (`reservation_id`),
  KEY `idx_status_create_time` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='预约死信台账';
