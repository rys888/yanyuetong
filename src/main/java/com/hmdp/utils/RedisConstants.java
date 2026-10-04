package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final String CACHE_EQUIPMENT_KEY = "lab:equipment:";
    public static final Long CACHE_EQUIPMENT_TTL = 30L;
    public static final Long CACHE_EQUIPMENT_TTL_JITTER = 5L;

    public static final String LOCK_CACHE_KEY = "lock:";
    public static final String LOCK_RESERVATION_KEY = "lock:reservation:";

    public static final String RESERVATION_BATCH_QUOTA_KEY = "reservation:batch:quota:";
    public static final String RESERVATION_BATCH_USERS_KEY = "reservation:batch:users:";
    public static final Long RESERVATION_BATCH_QUOTA_TTL_BUFFER_HOURS = 24L;

}
