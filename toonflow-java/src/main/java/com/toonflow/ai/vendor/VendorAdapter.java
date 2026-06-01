package com.toonflow.ai.vendor;

import com.toonflow.ai.vendor.dto.ImageConfig;
import com.toonflow.ai.vendor.dto.VideoConfig;

import java.util.Map;

/**
 * 厂商适配器接口
 *
 * 对应原项目厂商 TS 模板导出的 imageRequest / videoRequest / ttsRequest 三个函数。
 * 原项目用 vm2 沙箱动态执行脚本实现热插拔；Java 版改为每个厂商实现本接口的 Spring Bean，
 * 以 vendorId 作为 Bean 名称注册，由 VendorAdapterRegistry 按需查找。
 *
 * 新增厂商：实现本接口并用 @Component("厂商id") 注册即可。
 */
public interface VendorAdapter {

    /**
     * 厂商唯一标识（与 o_vendorConfig.id 对应）
     */
    String vendorId();

    /**
     * 图片生成
     * @param config 图片配置
     * @param inputs 厂商配置参数（apiKey、baseUrl 等，来自 o_vendorConfig.inputValues）
     * @return 生成图片的 URL 或 base64
     */
    String imageRequest(ImageConfig config, Map<String, String> inputs);

    /**
     * 视频生成（通常为异步：内部完成提交+轮询，返回最终视频地址）
     * @param config 视频配置
     * @param inputs 厂商配置参数
     * @return 生成视频的 URL 或 base64
     */
    default String videoRequest(VideoConfig config, Map<String, String> inputs) {
        throw new UnsupportedOperationException(vendorId() + " 不支持视频生成");
    }

    /**
     * 语音合成（TTS）
     * @param text 待合成文本
     * @param voice 音色
     * @param inputs 厂商配置参数
     * @return 生成音频的 URL 或 base64
     */
    default String ttsRequest(String text, String voice, Map<String, String> inputs) {
        throw new UnsupportedOperationException(vendorId() + " 不支持语音合成");
    }
}
