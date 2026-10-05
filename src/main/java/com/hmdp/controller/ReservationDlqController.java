package com.hmdp.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hmdp.annotation.RequireRole;
import com.hmdp.dto.ReservationDlqQueryDTO;
import com.hmdp.dto.Result;
import com.hmdp.enums.UserRole;
import com.hmdp.service.IReservationDlqService;
import com.hmdp.vo.ReservationDlqReplayVO;
import com.hmdp.vo.ReservationDlqVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/reservation-dlq")
public class ReservationDlqController {

    @Resource
    private IReservationDlqService reservationDlqService;

    @GetMapping("/page")
    @RequireRole(UserRole.LAB_ADMIN)
    public Result queryPage(ReservationDlqQueryDTO query) {
        IPage<ReservationDlqVO> page = reservationDlqService.queryPage(query);
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @GetMapping("/{id}")
    @RequireRole(UserRole.LAB_ADMIN)
    public Result queryById(@PathVariable("id") Long id) {
        ReservationDlqVO record = reservationDlqService.queryById(id);
        return record == null ? Result.fail("死信记录不存在") : Result.ok(record);
    }

    @PostMapping("/{id}/replay")
    @RequireRole(UserRole.LAB_ADMIN)
    public Result replay(@PathVariable("id") Long id) {
        ReservationDlqReplayVO result = reservationDlqService.replay(id);
        return result.isReplayed()
                ? Result.ok(result.getRecord())
                : Result.fail(result.getReason());
    }
}
