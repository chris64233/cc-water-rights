package com.chris64233.cc.waterrights.error;

import java.util.Map;

/**
 * 统一错误响应体：{@code {"code": "...", "message": "...", "fieldErrors": {...}}}。
 */
public record ApiError(String code, String message, Map<String, String> fieldErrors) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }
}
