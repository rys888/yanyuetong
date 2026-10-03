package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.EquipmentQueryDTO;
import com.hmdp.entity.Equipment;
import com.hmdp.entity.EquipmentCategory;
import com.hmdp.entity.Laboratory;
import com.hmdp.mapper.EquipmentMapper;
import com.hmdp.service.IEquipmentCategoryService;
import com.hmdp.service.IEquipmentService;
import com.hmdp.service.ILaboratoryService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.vo.EquipmentVO;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
public class EquipmentServiceImpl extends ServiceImpl<EquipmentMapper, Equipment> implements IEquipmentService {

    @Resource
    private ILaboratoryService laboratoryService;

    @Resource
    private IEquipmentCategoryService equipmentCategoryService;

    @Override
    public IPage<EquipmentVO> queryEquipmentPage(EquipmentQueryDTO query) {
        Integer current = query.getCurrent() == null ? 1 : query.getCurrent();
        Page<EquipmentVO> page = new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE);
        return getBaseMapper().selectEquipmentPage(page, query);
    }

    @Override
    public EquipmentVO queryEquipmentById(Long id) {
        Equipment equipment = getById(id);
        if (equipment == null) {
            return null;
        }
        EquipmentVO equipmentVO = BeanUtil.copyProperties(equipment, EquipmentVO.class);
        Laboratory laboratory = laboratoryService.getById(equipment.getLaboratoryId());
        if (laboratory != null) {
            equipmentVO.setLaboratoryName(laboratory.getName());
            equipmentVO.setCampus(laboratory.getCampus());
            equipmentVO.setBuilding(laboratory.getBuilding());
            equipmentVO.setRoom(laboratory.getRoom());
        }
        EquipmentCategory category = equipmentCategoryService.getById(equipment.getCategoryId());
        if (category != null) {
            equipmentVO.setCategoryName(category.getName());
        }
        return equipmentVO;
    }
}
