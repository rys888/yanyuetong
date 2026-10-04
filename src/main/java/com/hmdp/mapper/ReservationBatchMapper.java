package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hmdp.dto.ReservationBatchQueryDTO;
import com.hmdp.entity.ReservationBatch;
import com.hmdp.vo.ReservationBatchVO;
import org.apache.ibatis.annotations.Param;

public interface ReservationBatchMapper extends BaseMapper<ReservationBatch> {

    ReservationBatchVO selectBatchDetailById(@Param("id") Long id);

    IPage<ReservationBatchVO> selectReservationBatchPage(
            IPage<ReservationBatchVO> page,
            @Param("q") ReservationBatchQueryDTO query,
            @Param("adminUserId") Long adminUserId,
            @Param("publicOnly") boolean publicOnly);
}
