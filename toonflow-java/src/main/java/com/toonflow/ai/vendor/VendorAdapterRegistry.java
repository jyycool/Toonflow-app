package com.toonflow.ai.vendor;

import com.toonflow.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 厂商适配器注册中心
 *
 * 启动时收集所有 VendorAdapter Bean，按 vendorId() 建立索引。
 * 对应原项目运行时按 vendorId 查找并执行对应厂商脚本的能力。
 */
@Slf4j
@Component
public class VendorAdapterRegistry {

    private final Map<String, VendorAdapter> adapters = new HashMap<>();
    private final VendorAdapter fallbackAdapter;

    public VendorAdapterRegistry(List<VendorAdapter> adapterList,
                                 OpenAiCompatibleAdapter fallbackAdapter) {
        this.fallbackAdapter = fallbackAdapter;
        for (VendorAdapter adapter : adapterList) {
            adapters.put(adapter.vendorId(), adapter);
            log.info("注册厂商适配器: {} -> {}", adapter.vendorId(), adapter.getClass().getSimpleName());
        }
    }

    /**
     * 按 vendorId 获取适配器，未注册专用适配器时回退到 OpenAI 兼容适配器
     */
    public VendorAdapter get(String vendorId) {
        VendorAdapter adapter = adapters.get(vendorId);
        if (adapter != null) return adapter;
        log.debug("厂商 {} 无专用适配器，使用 OpenAI 兼容适配器", vendorId);
        return fallbackAdapter;
    }

    /**
     * 是否存在专用适配器
     */
    public boolean hasDedicatedAdapter(String vendorId) {
        return adapters.containsKey(vendorId);
    }
}
