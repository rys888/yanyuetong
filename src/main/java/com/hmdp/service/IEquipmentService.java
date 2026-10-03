package com.hmdp.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.EquipmentQueryDTO;
import com.hmdp.entity.Equipment;
import com.hmdp.vo.EquipmentVO;

public interface IEquipmentService extends IService<Equipment> {

    IPage<EquipmentVO> queryEquipmentPage(EquipmentQueryDTO query);

    EquipmentVO queryEquipmentById(Long id);
}
