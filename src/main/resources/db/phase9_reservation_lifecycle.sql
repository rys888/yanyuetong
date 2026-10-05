-- Phase 9：预约取消与履约生命周期。
ALTER TABLE `tb_reservation`
  ADD COLUMN `status` varchar(16) NOT NULL DEFAULT 'RESERVED' COMMENT '生命周期状态',
  ADD COLUMN `quota_released` tinyint NOT NULL DEFAULT 0 COMMENT '取消时名额是否已归还Redis',
  ADD COLUMN `active_flag` tinyint GENERATED ALWAYS AS (CASE WHEN `status`='CANCELLED' THEN NULL ELSE 1 END) STORED COMMENT '仅约束活跃预约',
  ADD KEY `idx_status_quota_released` (`status`,`quota_released`);

ALTER TABLE `tb_reservation`
  DROP INDEX `uk_user_batch`,
  ADD UNIQUE KEY `uk_user_batch_active` (`user_id`,`batch_id`,`active_flag`);

-- 回滚步骤：
-- 1. 回滚前必须先删除同一 (user_id, batch_id) 键下的已取消行，否则重建旧唯一键会失败。
-- 2. DROP INDEX `uk_user_batch_active` ON `tb_reservation`；
-- 3. ALTER TABLE `tb_reservation` DROP INDEX `idx_status_quota_released`,
--      DROP COLUMN `active_flag`, DROP COLUMN `quota_released`, DROP COLUMN `status`；
-- 4. ALTER TABLE `tb_reservation`
--      ADD UNIQUE KEY `uk_user_batch` (`user_id`,`batch_id`)；
