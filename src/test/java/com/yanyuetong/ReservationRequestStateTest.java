package com.yanyuetong;

import com.yanyuetong.entity.ReservationRequest;
import com.yanyuetong.enums.ReservationRequestStatus;
import com.yanyuetong.mapper.ReservationRequestMapper;
import com.yanyuetong.service.IReservationRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static com.yanyuetong.enums.ReservationRequestStatus.COMPENSATED;
import static com.yanyuetong.enums.ReservationRequestStatus.CONFIRMED;
import static com.yanyuetong.enums.ReservationRequestStatus.PRE_ACCEPTED;
import static com.yanyuetong.enums.ReservationRequestStatus.PUBLISHED;
import static com.yanyuetong.enums.ReservationRequestStatus.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ReservationRequestStateTest {

    @Resource
    private IReservationRequestService service;

    @Resource
    private ReservationRequestMapper reservationRequestMapper;

    private final List<Long> createdRequestIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdRequestIds.forEach(reservationRequestMapper::deleteById);
        createdRequestIds.clear();
    }

    @Test
    void shouldCreateAndGetPreAcceptedRequest() {
        long requestId = nextRequestId();
        Long userId = 9_200_000_001L;
        Long batchId = 9_300_000_001L;

        service.createPreAccepted(requestId, userId, batchId);

        ReservationRequest request = service.getByRequestId(requestId);
        assertNotNull(request);
        assertEquals(Long.valueOf(requestId), request.getRequestId());
        assertEquals(userId, request.getUserId());
        assertEquals(batchId, request.getBatchId());
        assertEquals(PRE_ACCEPTED, request.getStatus());
    }

    @Test
    void shouldUpdateStatusWhenExpectedStatusMatches() {
        long requestId = createPreAcceptedRequest();

        assertTrue(service.casStatus(requestId, PUBLISHED, PRE_ACCEPTED));
        assertEquals(PUBLISHED, service.getByRequestId(requestId).getStatus());
    }

    @Test
    void shouldKeepStatusWhenExpectedStatusDoesNotMatch() {
        long requestId = createPreAcceptedRequest();
        assertTrue(service.casStatus(requestId, PUBLISHED, PRE_ACCEPTED));

        assertFalse(service.casStatus(requestId, COMPENSATED, PRE_ACCEPTED));
        assertEquals(PUBLISHED, service.getByRequestId(requestId).getStatus());
    }

    @Test
    void shouldFreezeTerminalOnlyViaExpectedStatuses() {
        long requestId = createPreAcceptedRequest();
        assertTrue(service.casStatus(requestId, COMPENSATED, PRE_ACCEPTED));
        assertEquals(COMPENSATED, service.getByRequestId(requestId).getStatus());

        // 调用方一律传 nonTerminalStatuses()；传终态作为期望值是测试专用，生产代码不得这么用。
        ReservationRequestStatus[] nonTerminal = ReservationRequestStatus.nonTerminalStatuses()
                .toArray(new ReservationRequestStatus[0]);
        assertFalse(service.casStatus(requestId, CONFIRMED, nonTerminal));
        assertEquals(COMPENSATED, service.getByRequestId(requestId).getStatus());

        assertTrue(service.casStatus(requestId, CONFIRMED, COMPENSATED));
        assertEquals(CONFIRMED, service.getByRequestId(requestId).getStatus());
    }

    @Test
    void shouldRejectEmptyExpectedStatuses() {
        long requestId = createPreAcceptedRequest();

        assertThrows(IllegalArgumentException.class,
                () -> service.casStatus(requestId, PUBLISHED));
    }

    @Test
    void shouldRecordFailReasonWithoutChangingStatus() {
        long requestId = createPreAcceptedRequest();

        assertTrue(service.recordFailReason(requestId, "消息处理失败"));

        ReservationRequest request = service.getByRequestId(requestId);
        assertEquals("消息处理失败", request.getFailReason());
        assertEquals(PRE_ACCEPTED, request.getStatus());
    }

    @Test
    void shouldListOnlyStaleNonTerminalRequestsWithinLimit() {
        long preAcceptedId = createPreAcceptedRequest();
        long publishedId = createPreAcceptedRequest();
        assertTrue(service.casStatus(publishedId, PUBLISHED, PRE_ACCEPTED));
        long unknownId = createPreAcceptedRequest();
        assertTrue(service.casStatus(unknownId, UNKNOWN, PRE_ACCEPTED));
        long compensatedId = createPreAcceptedRequest();
        assertTrue(service.casStatus(compensatedId, COMPENSATED, PRE_ACCEPTED));
        long confirmedId = createPreAcceptedRequest();
        assertTrue(service.casStatus(confirmedId, CONFIRMED, PRE_ACCEPTED));
        LocalDateTime before = LocalDateTime.now().plusMinutes(1);

        List<ReservationRequest> stale = service.listStale(before, 200);
        List<Long> fixtureIds = stale.stream()
                .map(ReservationRequest::getRequestId)
                .filter(id -> id == preAcceptedId || id == publishedId || id == unknownId
                        || id == compensatedId || id == confirmedId)
                .collect(Collectors.toList());

        // 防止 nonTerminalStatuses() 收窄后静默漏扫任一非终态。
        assertEquals(3, fixtureIds.size());
        assertTrue(fixtureIds.contains(preAcceptedId));
        assertTrue(fixtureIds.contains(publishedId));
        assertTrue(fixtureIds.contains(unknownId));
        assertFalse(fixtureIds.contains(compensatedId));
        assertFalse(fixtureIds.contains(confirmedId));
        assertTrue(stale.stream().allMatch(request -> !request.getStatus().isTerminal()));
        assertEquals(1, service.listStale(before, 1).size());

        // 阈值取「过去」：五条夹具的 update_time 都晚于它，必须一条都扫不到。
        // 没有这条断言，把 update_time < before 谓词整行删掉本测试仍会全绿（已用删谓词变体实测证实）。
        LocalDateTime past = LocalDateTime.now().minusMinutes(5);
        List<Long> olderThanPast = service.listStale(past, 200).stream()
                .map(ReservationRequest::getRequestId)
                .filter(id -> id == preAcceptedId || id == publishedId || id == unknownId
                        || id == compensatedId || id == confirmedId)
                .collect(Collectors.toList());
        assertTrue(olderThanPast.isEmpty(), "早于阈值 before 的账本不应被 listStale 扫到");
    }

    @Test
    void shouldOrderTiedUpdateTimesDeterministicallyByAscendingRequestId() {
        long lowerRequestId = nextRequestId();
        long higherRequestId = lowerRequestId + 1;
        createdRequestIds.add(higherRequestId);
        // 反向插入，防止测试把插入顺序误当成并列时间戳的排序契约。
        service.createPreAccepted(higherRequestId, 9_200_000_001L, 9_300_000_001L);
        service.createPreAccepted(lowerRequestId, 9_200_000_001L, 9_300_000_001L);
        assertTrue(service.casStatus(lowerRequestId, UNKNOWN, PRE_ACCEPTED));
        LocalDateTime tiedUpdateTime = LocalDateTime.of(2020, 1, 2, 3, 4, 5);
        assertEquals(1, reservationRequestMapper.updateById(new ReservationRequest()
                .setRequestId(higherRequestId)
                .setUpdateTime(tiedUpdateTime)));
        assertEquals(1, reservationRequestMapper.updateById(new ReservationRequest()
                .setRequestId(lowerRequestId)
                .setUpdateTime(tiedUpdateTime)));

        List<Long> firstOrder = service.listStale(tiedUpdateTime.plusSeconds(1), 200).stream()
                .map(ReservationRequest::getRequestId)
                .filter(id -> id == lowerRequestId || id == higherRequestId)
                .collect(Collectors.toList());
        List<Long> secondOrder = service.listStale(tiedUpdateTime.plusSeconds(1), 200).stream()
                .map(ReservationRequest::getRequestId)
                .filter(id -> id == lowerRequestId || id == higherRequestId)
                .collect(Collectors.toList());

        // 秒级时间戳并列时必须由 request_id 明确升序，保证每轮 LIMIT 选取可复现。
        assertEquals(Arrays.asList(lowerRequestId, higherRequestId), firstOrder);
        assertEquals(firstOrder, secondOrder);
    }

    private long createPreAcceptedRequest() {
        long requestId = nextRequestId();
        service.createPreAccepted(requestId, 9_200_000_001L, 9_300_000_001L);
        return requestId;
    }

    private long nextRequestId() {
        long requestId = ThreadLocalRandom.current().nextLong(
                9_000_000_000L, 9_100_000_000L);
        createdRequestIds.add(requestId);
        return requestId;
    }
}
