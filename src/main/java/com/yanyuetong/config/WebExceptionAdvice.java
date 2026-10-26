package com.yanyuetong.config;

import com.yanyuetong.dto.Result;
import com.yanyuetong.exception.BadRequestException;
import com.yanyuetong.exception.ForbiddenException;
import com.yanyuetong.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 把业务异常转换为 HTTP 状态和统一 Result：参数错误 400、归属拒绝 403、暂时不可用 503。
 * 预约投递结果未知也会返回 503，不能仅凭 HTTP 失败断言未落库，仍需结合请求 ID 查询或等待对账。
 * 未预期的运行时异常记录详细日志，但只向客户端返回通用 500 信息。
 */
@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(BadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result handleBadRequest(BadRequestException e) {
        log.warn("请求不合法：{}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result handleForbidden(ForbiddenException e) {
        log.warn("归属校验未通过：{}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result handleServiceUnavailable(ServiceUnavailableException e) {
        log.warn("服务暂时不可用：{}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        return Result.fail("请求体格式错误");
    }
}
