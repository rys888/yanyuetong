package com.yanyuetong.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yanyuetong.dto.ReservationBatchQueryDTO;
import com.yanyuetong.entity.ReservationBatch;
import com.yanyuetong.vo.ReservationBatchVO;
import org.apache.ibatis.annotations.Param;

public interface ReservationBatchMapper extends BaseMapper<ReservationBatch> {

    ReservationBatchVO selectBatchDetailById(@Param("id") Long id);

    IPage<ReservationBatchVO> selectReservationBatchPage(
            IPage<ReservationBatchVO> page,
            @Param("q") ReservationBatchQueryDTO query,
            @Param("adminUserId") Long adminUserId,
            @Param("publicOnly") boolean publicOnly);
}
