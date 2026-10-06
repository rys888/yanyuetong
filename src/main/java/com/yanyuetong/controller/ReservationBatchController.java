package com.yanyuetong.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yanyuetong.annotation.RequireRole;
import com.yanyuetong.dto.ReservationBatchQueryDTO;
import com.yanyuetong.dto.Result;
import com.yanyuetong.entity.ReservationBatch;
import com.yanyuetong.enums.UserRole;
import com.yanyuetong.service.IReservationBatchService;
import com.yanyuetong.vo.ReservationBatchVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/reservation-batch")
public class ReservationBatchController {

    @Resource
    private IReservationBatchService batchService;

    @PostMapping
    @RequireRole(UserRole.LAB_ADMIN)
    public Result createBatch(@RequestBody ReservationBatch batch) {
        return Result.ok(batchService.createBatch(batch));
    }

    @PutMapping
    @RequireRole(UserRole.LAB_ADMIN)
    public Result updateDraft(@RequestBody ReservationBatch batch) {
        batchService.updateDraft(batch);
        return Result.ok();
    }

    @PostMapping("/{id}/publish")
    @RequireRole(UserRole.LAB_ADMIN)
    public Result publish(@PathVariable("id") Long id) {
        batchService.publish(id);
        return Result.ok();
    }

    @PostMapping("/{id}/close")
    @RequireRole(UserRole.LAB_ADMIN)
    public Result close(@PathVariable("id") Long id) {
        batchService.close(id);
        return Result.ok();
    }

    @PostMapping("/{id}/cancel")
    @RequireRole(UserRole.LAB_ADMIN)
    public Result cancel(@PathVariable("id") Long id) {
        batchService.cancel(id);
        return Result.ok();
    }

    @GetMapping("/{id}")
    public Result queryBatchById(@PathVariable("id") Long id) {
        ReservationBatchVO batch = batchService.queryBatchById(id);
        return batch == null ? Result.fail("预约批次不存在") : Result.ok(batch);
    }

    @GetMapping("/page")
    public Result queryBatchPage(ReservationBatchQueryDTO query) {
        IPage<ReservationBatchVO> page = batchService.queryBatchPage(query);
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @GetMapping("/manage/page")
    @RequireRole(UserRole.LAB_ADMIN)
    public Result queryManagePage(ReservationBatchQueryDTO query) {
        IPage<ReservationBatchVO> page = batchService.queryManagePage(query);
        return Result.ok(page.getRecords(), page.getTotal());
    }
}
