package com.yanyuetong.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.yanyuetong.dto.EquipmentQueryDTO;
import com.yanyuetong.entity.Equipment;
import com.yanyuetong.enums.EquipmentStatus;
import com.yanyuetong.vo.EquipmentVO;

public interface IEquipmentService extends IService<Equipment> {

    IPage<EquipmentVO> queryEquipmentPage(EquipmentQueryDTO query);

    EquipmentVO queryEquipmentById(Long id);

    Long saveEquipment(Equipment equipment);

    void updateEquipment(Equipment equipment);

    void updateEquipmentStatus(Long id, EquipmentStatus status);

    void deleteEquipment(Long id);
}
