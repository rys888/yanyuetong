package com.yanyuetong.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yanyuetong.dto.ReservationQueryDTO;
import com.yanyuetong.entity.Reservation;
import com.yanyuetong.vo.ReservationVO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationMapper extends BaseMapper<Reservation> {

    ReservationVO selectReservationDetailById(@Param("id") Long id);

    IPage<ReservationVO> selectReservationPage(
            IPage<ReservationVO> page,
            @Param("q") ReservationQueryDTO query,
            @Param("userId") Long userId,
            @Param("adminUserId") Long adminUserId);

    List<Reservation> selectPendingQuotaReleases(@Param("limit") int limit);

    int markNoShows(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
