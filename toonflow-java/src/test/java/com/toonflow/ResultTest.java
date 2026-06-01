package com.toonflow;

import com.toonflow.common.result.R;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 统一响应格式单元测试
 */
class ResultTest {

    @Test
    void ok_setsSuccessAndData() {
        R<String> r = R.ok("hello");
        assertTrue(r.isSuccess());
        assertEquals(200, r.getCode());
        assertEquals("hello", r.getData());
    }

    @Test
    void ok_withMessage() {
        R<String> r = R.ok("data", "操作成功");
        assertTrue(r.isSuccess());
        assertEquals("操作成功", r.getMessage());
    }

    @Test
    void fail_setsFailureAndCode() {
        R<Void> r = R.fail("出错了");
        assertFalse(r.isSuccess());
        assertEquals(400, r.getCode());
        assertEquals("出错了", r.getMessage());

        R<Void> r2 = R.fail(444, "秘钥未配置");
        assertEquals(444, r2.getCode());
    }
}
