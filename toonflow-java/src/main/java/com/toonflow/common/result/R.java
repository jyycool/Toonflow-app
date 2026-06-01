package com.toonflow.common.result;

import lombok.Data;

@Data
public class R<T> {
    private boolean success;
    private int code;
    private String message;
    private T data;

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.success = true;
        r.code = 200;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> R<T> ok(T data, String message) {
        R<T> r = ok(data);
        r.message = message;
        return r;
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(String message) {
        R<T> r = new R<>();
        r.success = false;
        r.code = 400;
        r.message = message;
        return r;
    }

    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.success = false;
        r.code = code;
        r.message = message;
        return r;
    }
}
