SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_laboratory' AND COLUMN_NAME = 'admin_user_id');
SET @sql := IF(@col = 0,
  'ALTER TABLE `tb_laboratory` ADD COLUMN `admin_user_id` bigint unsigned NULL COMMENT ''负责管理员用户id'' AFTER `status`, ADD KEY `idx_admin_user_id` (`admin_user_id`)',
  'SELECT ''tb_laboratory.admin_user_id already exists'' AS skipped');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO `tb_user` (`phone`, `nick_name`, `icon`, `role`)
VALUES ('13900000002', '实验室管理员B', '', 'LAB_ADMIN')
ON DUPLICATE KEY UPDATE `role` = 'LAB_ADMIN';

UPDATE `tb_laboratory`
SET `admin_user_id` = (SELECT `id` FROM `tb_user` WHERE `phone` = '13900000001')
WHERE `id` IN (1, 2) AND `admin_user_id` IS NULL;

UPDATE `tb_laboratory`
SET `admin_user_id` = (SELECT `id` FROM `tb_user` WHERE `phone` = '13900000002')
WHERE `id` IN (3, 4) AND `admin_user_id` IS NULL;
