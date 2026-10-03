package com.hmdp.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hmdp.dto.EquipmentQueryDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.Equipment;
import com.hmdp.enums.EquipmentStatus;
import com.hmdp.service.IEquipmentService;
import com.hmdp.vo.EquipmentVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/equipment")
public class EquipmentController {

    @Resource
    private IEquipmentService equipmentService;

    @GetMapping("/{id}")
    public Result queryEquipmentById(@PathVariable("id") Long id) {
        EquipmentVO equipment = equipmentService.queryEquipmentById(id);
        if (equipment == null) {
            return Result.fail("设备不存在");
        }
        return Result.ok(equipment);
    }

    @GetMapping("/page")
    public Result queryEquipmentPage(EquipmentQueryDTO query) {
        IPage<EquipmentVO> page = equipmentService.queryEquipmentPage(query);
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @PostMapping
    public Result saveEquipment(@RequestBody Equipment equipment) {
        equipmentService.save(equipment);
        return Result.ok(equipment.getId());
    }

    @PutMapping
    public Result updateEquipment(@RequestBody Equipment equipment) {
        equipmentService.updateById(equipment);
        return Result.ok();
    }

    @PutMapping("/{id}/status")
    public Result updateEquipmentStatus(
            @PathVariable("id") Long id,
            @RequestParam("status") String statusValue) {
        EquipmentStatus status;
        try {
            status = EquipmentStatus.valueOf(statusValue);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Result.fail("设备状态不合法");
        }
        Equipment equipment = new Equipment().setId(id).setStatus(status);
        equipmentService.updateById(equipment);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result deleteEquipment(@PathVariable("id") Long id) {
        equipmentService.removeById(id);
        return Result.ok();
    }
}
