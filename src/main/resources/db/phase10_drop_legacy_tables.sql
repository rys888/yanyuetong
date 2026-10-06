-- Phase 10：历史课程死表清理。
-- 本脚本不可逆地删除课程演示数据（tb_shop 14 行 / tb_blog 4 行 / tb_voucher 1 行），仅在确认不再需要这些数据时执行。

-- 原课程探店笔记表；已无代码引用；DROP 时点 4 行。
DROP TABLE IF EXISTS `tb_blog`;

-- 原课程笔记评论表；已无代码引用；DROP 时点 0 行。
DROP TABLE IF EXISTS `tb_blog_comments`;

-- 原课程用户关注关系表；已无代码引用；DROP 时点 0 行。
DROP TABLE IF EXISTS `tb_follow`;

-- 原课程用户签到表；已无代码引用；DROP 时点 0 行。
DROP TABLE IF EXISTS `tb_sign`;

-- 原课程商铺表；已无代码引用；DROP 时点 14 行。
DROP TABLE IF EXISTS `tb_shop`;

-- 原课程商铺类型表；已无代码引用；DROP 时点 10 行。
DROP TABLE IF EXISTS `tb_shop_type`;

-- 原课程秒杀券库存与时段表；已无代码引用；DROP 时点 0 行。
DROP TABLE IF EXISTS `tb_seckill_voucher`;

-- 原课程用户详情表；已无代码引用；DROP 时点 0 行。
DROP TABLE IF EXISTS `tb_user_info`;

-- 原课程优惠券表；已无代码引用；DROP 时点 1 行。
DROP TABLE IF EXISTS `tb_voucher`;

-- 原课程优惠券订单表；已无代码引用；DROP 时点 0 行。
DROP TABLE IF EXISTS `tb_voucher_order`;
