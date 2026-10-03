SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_user' AND COLUMN_NAME = 'role');
SET @sql := IF(@col = 0,
  'ALTER TABLE `tb_user` ADD COLUMN `role` varchar(16) NOT NULL DEFAULT ''USER'' COMMENT ''角色：USER/LAB_ADMIN'' AFTER `icon`',
  'SELECT ''tb_user.role already exists'' AS skipped');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO `tb_user` (`phone`, `nick_name`, `icon`, `role`)
VALUES ('13900000001', '实验室管理员', '', 'LAB_ADMIN')
ON DUPLICATE KEY UPDATE `role` = 'LAB_ADMIN';
