CREATE TABLE IF NOT EXISTS `tb_reservation` (
  `id`          bigint unsigned NOT NULL COMMENT '预约id（RedisIdWorker 生成，非自增）',
  `user_id`     bigint unsigned NOT NULL COMMENT '用户id',
  `batch_id`    bigint unsigned NOT NULL COMMENT '预约批次id',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_batch` (`user_id`, `batch_id`) USING BTREE,
  KEY `idx_batch_id` (`batch_id`) USING BTREE,
  KEY `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='预约记录';
