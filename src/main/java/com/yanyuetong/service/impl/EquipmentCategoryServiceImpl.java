package com.yanyuetong.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yanyuetong.entity.EquipmentCategory;
import com.yanyuetong.mapper.EquipmentCategoryMapper;
import com.yanyuetong.service.IEquipmentCategoryService;
import org.springframework.stereotype.Service;

@Service
public class EquipmentCategoryServiceImpl extends ServiceImpl<EquipmentCategoryMapper, EquipmentCategory> implements IEquipmentCategoryService {
}
