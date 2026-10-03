package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.EquipmentCategory;
import com.hmdp.mapper.EquipmentCategoryMapper;
import com.hmdp.service.IEquipmentCategoryService;
import org.springframework.stereotype.Service;

@Service
public class EquipmentCategoryServiceImpl extends ServiceImpl<EquipmentCategoryMapper, EquipmentCategory> implements IEquipmentCategoryService {
}
