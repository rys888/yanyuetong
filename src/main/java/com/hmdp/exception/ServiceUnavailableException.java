package com.hmdp.exception;

/** 下游服务暂时不可用，当前请求可稍后重试。 */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}
