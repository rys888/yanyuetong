CREATE TABLE IF NOT EXISTS `tb_reservation_batch` (
  `id`                 bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `equipment_id`       bigint unsigned NOT NULL COMMENT '设备id',
  `start_time`         datetime NOT NULL COMMENT '使用开始时间',
  `end_time`           datetime NOT NULL COMMENT '使用结束时间',
  `booking_start_time` datetime NOT NULL COMMENT '预约开放时间',
  `booking_end_time`   datetime NOT NULL COMMENT '预约截止时间',
  `total_quota`        int NOT NULL COMMENT '总名额',
  `remaining_quota`    int NOT NULL COMMENT '剩余名额',
  `status`             varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/PUBLISHED/CLOSED/CANCELLED',
  `create_time`        timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`        timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_equipment_id` (`equipment_id`) USING BTREE,
  KEY `idx_status` (`status`) USING BTREE,
  KEY `idx_booking_window` (`booking_start_time`, `booking_end_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备预约批次';
