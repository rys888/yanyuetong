CREATE TABLE IF NOT EXISTS `tb_laboratory` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(64) NOT NULL COMMENT '实验室名称',
  `campus` varchar(64) NOT NULL COMMENT '所属校区',
  `building` varchar(64) DEFAULT NULL COMMENT '楼栋',
  `room` varchar(32) DEFAULT NULL COMMENT '房间号',
  `description` varchar(512) DEFAULT NULL COMMENT '实验室简介',
  `status` varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT '状态：OPEN/CLOSED/MAINTENANCE',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_campus` (`campus`) USING BTREE,
  KEY `idx_name` (`name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='实验室';

CREATE TABLE IF NOT EXISTS `tb_equipment_category` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(64) NOT NULL COMMENT '分类名称',
  `icon` varchar(255) DEFAULT NULL COMMENT '图标',
  `sort` int NOT NULL DEFAULT 0 COMMENT '排序',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_name` (`name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备分类';

CREATE TABLE IF NOT EXISTS `tb_equipment` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `laboratory_id` bigint unsigned NOT NULL COMMENT '所属实验室id',
  `category_id` bigint unsigned NOT NULL COMMENT '设备分类id',
  `name` varchar(128) NOT NULL COMMENT '设备名称',
  `description` varchar(1024) DEFAULT NULL COMMENT '设备说明',
  `location` varchar(128) DEFAULT NULL COMMENT '实验室内具体位置',
  `image` varchar(255) DEFAULT NULL COMMENT '图片URL',
  `usage_requirement` varchar(512) DEFAULT NULL COMMENT '使用要求',
  `status` varchar(16) NOT NULL DEFAULT 'AVAILABLE' COMMENT '状态：AVAILABLE/MAINTENANCE/DISABLED',
  `booking_enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否开放预约',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_laboratory_id` (`laboratory_id`) USING BTREE,
  KEY `idx_category_id` (`category_id`) USING BTREE,
  KEY `idx_name` (`name`) USING BTREE,
  KEY `idx_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='实验设备';

INSERT INTO `tb_laboratory` (`id`, `name`, `campus`, `building`, `room`, `description`, `status`) VALUES
  (1, '材料科学实验中心', '主校区', '材料楼', 'A101', '承担材料微观结构与性能分析实验。', 'OPEN'),
  (2, '分析测试实验室', '主校区', '理科楼', 'B205', '提供色谱、质谱与光谱分析服务。', 'OPEN'),
  (3, '先进制造实验室', '东校区', '工程训练中心', 'C108', '面向增材制造与精密加工教学科研。', 'OPEN'),
  (4, '计算与样品制备中心', '东校区', '信息楼', 'D302', '提供高性能计算与通用样品制备条件。', 'MAINTENANCE');

INSERT INTO `tb_equipment_category` (`id`, `name`, `icon`, `sort`) VALUES
  (1, '显微成像', NULL, 1),
  (2, '化学分析', NULL, 2),
  (3, '机械加工', NULL, 3),
  (4, '计算资源', NULL, 4),
  (5, '光谱分析', NULL, 5),
  (6, '样品制备', NULL, 6);

INSERT INTO `tb_equipment` (`id`, `laboratory_id`, `category_id`, `name`, `description`, `location`, `image`, `usage_requirement`, `status`, `booking_enabled`) VALUES
  (1, 1, 1, '扫描电子显微镜', '用于材料表面微观形貌观察。', 'A101-1', NULL, '需完成显微镜操作培训。', 'AVAILABLE', 1),
  (2, 1, 1, '透射电子显微镜', '用于材料内部微观结构分析。', 'A101-2', NULL, '须由实验室管理员审核。', 'MAINTENANCE', 0),
  (3, 2, 2, '高效液相色谱仪', '用于复杂混合物分离与定量。', 'B205-1', NULL, '自备符合要求的流动相。', 'AVAILABLE', 1),
  (4, 2, 2, '气相色谱质谱联用仪', '用于挥发性化合物定性定量。', 'B205-2', NULL, '需提交样品安全说明。', 'AVAILABLE', 1),
  (5, 3, 3, '3D 打印设备', '用于高分子材料快速成型。', 'C108-1', NULL, '需上传打印模型并通过审核。', 'AVAILABLE', 1),
  (6, 3, 3, '数控加工中心', '用于金属零件精密加工。', 'C108-2', NULL, '仅限取得机加工操作资格人员。', 'MAINTENANCE', 0),
  (7, 4, 4, 'GPU 工作站', '用于深度学习与科学计算。', 'D302-1', NULL, '预约时注明软件环境需求。', 'AVAILABLE', 1),
  (8, 4, 4, '高性能计算节点', '用于并行计算任务。', 'D302-2', NULL, '需提供计算资源需求说明。', 'AVAILABLE', 1),
  (9, 1, 5, 'X 射线衍射仪', '用于材料物相与晶体结构分析。', 'A101-3', NULL, '粉末样品需密封包装。', 'AVAILABLE', 1),
  (10, 2, 5, '紫外可见分光光度计', '用于溶液吸收光谱测量。', 'B205-3', NULL, '使用前检查比色皿洁净度。', 'AVAILABLE', 1),
  (11, 4, 6, '冷冻切片机', '用于低温条件下组织切片。', 'D302-3', NULL, '需佩戴低温防护用品。', 'MAINTENANCE', 1),
  (12, 4, 6, '超速离心机', '用于生物样品高速分离。', 'D302-4', NULL, '转子必须完成动平衡检查。', 'AVAILABLE', 1);
