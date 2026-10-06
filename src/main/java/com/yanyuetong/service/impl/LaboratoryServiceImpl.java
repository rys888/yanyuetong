package com.yanyuetong.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yanyuetong.dto.UserDTO;
import com.yanyuetong.entity.Equipment;
import com.yanyuetong.entity.Laboratory;
import com.yanyuetong.exception.BadRequestException;
import com.yanyuetong.exception.ForbiddenException;
import com.yanyuetong.mapper.EquipmentMapper;
import com.yanyuetong.mapper.LaboratoryMapper;
import com.yanyuetong.service.ILaboratoryService;
import com.yanyuetong.utils.UserHolder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
public class LaboratoryServiceImpl extends ServiceImpl<LaboratoryMapper, Laboratory> implements ILaboratoryService {

    @Resource
    private EquipmentMapper equipmentMapper;

    @Override
    public void assertManageable(Long laboratoryId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            throw new ForbiddenException("无权管理该实验室");
        }
        Laboratory laboratory = getById(laboratoryId);
        if (laboratory == null || !user.getId().equals(laboratory.getAdminUserId())) {
            throw new ForbiddenException("无权管理该实验室");
        }
    }

    @Override
    public Long saveLaboratory(Laboratory laboratory) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            throw new ForbiddenException("无权管理该实验室");
        }
        laboratory.setId(null);
        laboratory.setAdminUserId(user.getId());
        save(laboratory);
        return laboratory.getId();
    }

    @Override
    public void updateLaboratory(Laboratory laboratory) {
        assertManageable(laboratory.getId());
        laboratory.setAdminUserId(null);
        updateById(laboratory);
    }

    @Override
    public void deleteLaboratory(Long id) {
        assertManageable(id);
        Integer equipmentCount = equipmentMapper.selectCount(
                new LambdaQueryWrapper<Equipment>()
                        .eq(Equipment::getLaboratoryId, id));
        if (equipmentCount > 0) {
            throw new BadRequestException(
                    "该实验室下仍有 " + equipmentCount + " 台设备，请先转移或删除设备");
        }
        removeById(id);
    }
}
