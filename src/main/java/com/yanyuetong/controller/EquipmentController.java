package com.yanyuetong.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yanyuetong.annotation.RequireRole;
import com.yanyuetong.dto.EquipmentQueryDTO;
import com.yanyuetong.dto.Result;
import com.yanyuetong.entity.Equipment;
import com.yanyuetong.enums.EquipmentStatus;
import com.yanyuetong.enums.UserRole;
import com.yanyuetong.service.IEquipmentService;
import com.yanyuetong.vo.EquipmentVO;
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
    @RequireRole(UserRole.LAB_ADMIN)
    public Result saveEquipment(@RequestBody Equipment equipment) {
        return Result.ok(equipmentService.saveEquipment(equipment));
    }

    @PutMapping
    @RequireRole(UserRole.LAB_ADMIN)
    public Result updateEquipment(@RequestBody Equipment equipment) {
        equipmentService.updateEquipment(equipment);
        return Result.ok();
    }

    @PutMapping("/{id}/status")
    @RequireRole(UserRole.LAB_ADMIN)
    public Result updateEquipmentStatus(
            @PathVariable("id") Long id,
            @RequestParam("status") String statusValue) {
        EquipmentStatus status;
        try {
            status = EquipmentStatus.valueOf(statusValue);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Result.fail("设备状态不合法");
        }
        equipmentService.updateEquipmentStatus(id, status);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @RequireRole(UserRole.LAB_ADMIN)
    public Result deleteEquipment(@PathVariable("id") Long id) {
        equipmentService.deleteEquipment(id);
        return Result.ok();
    }
}
