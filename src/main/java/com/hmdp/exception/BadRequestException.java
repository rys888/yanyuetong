package com.hmdp.exception;

/** 请求参数或业务状态不满足批次生命周期约束。 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
