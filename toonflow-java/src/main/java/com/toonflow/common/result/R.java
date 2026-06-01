package com.toonflow.common.result;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * 统一响应格式
 *
 * 序列化结构与原项目 responseFormat.ts 完全一致：{ code, data, message }。
 * success 字段仅供后端内部使用，不参与 JSON 序列化（@JsonIgnore），
 * 以保证原 web 前端 100% 兼容。
 */
@Data
public class R<T> {

    @JsonIgnore
    private boolean success;

    private int code;
    private T data;
    private String message;

    public static <T> R<T> ok(T data) {
        return ok(data, "成功");
    }

    public static <T> R<T> ok(T data, String message) {
        R<T> r = new R<>();
        r.success = true;
        r.code = 200;
        r.message = message;
        r.data = data;
        return r;
    }

    public static <T> R<T> ok() {
        return ok(null, "成功");
    }

    public static <T> R<T> fail(String message) {
        return fail(400, message);
    }

    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.success = false;
        r.code = code;
        r.message = message;
        r.data = null;
        return r;
    }
}
