package com.yanyuetong.controller;

import com.yanyuetong.dto.Result;
import com.yanyuetong.entity.EquipmentCategory;
import com.yanyuetong.service.IEquipmentCategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

@RestController
@RequestMapping("/equipment-category")
public class EquipmentCategoryController {

    @Resource
    private IEquipmentCategoryService equipmentCategoryService;

    @GetMapping("/list")
    public Result queryCategoryList() {
        List<EquipmentCategory> categories = equipmentCategoryService.query().orderByAsc("sort").list();
        return Result.ok(categories);
    }
}
