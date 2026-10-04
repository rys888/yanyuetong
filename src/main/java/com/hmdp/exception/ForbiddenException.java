package com.hmdp.exception;

/** 归属校验未通过（含资源不存在的情形，故意不区分）。 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
