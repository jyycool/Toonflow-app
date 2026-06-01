package com.toonflow.ai.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.ai.vendor.MediaGenerationService;
import com.toonflow.entity.OAssets;
import com.toonflow.entity.OImageFlow;
import com.toonflow.entity.OScriptAssets;
import com.toonflow.entity.OStoryboard;
import com.toonflow.mapper.OAssetsMapper;
import com.toonflow.mapper.OImageFlowMapper;
import com.toonflow.mapper.OScriptAssetsMapper;
import com.toonflow.mapper.OStoryboardMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * 制作 Agent 工具集
 * 对应原项目 src/agents/productionAgent/tools.ts
 *
 * 提供衍生资产的增删、图片生成、分镜生成等操作给大模型自主调用。
 */
@Slf4j
public class ProductionAgentTools {

    private final OAssetsMapper assetsMapper;
    private final OScriptAssetsMapper scriptAssetsMapper;
    private final OStoryboardMapper storyboardMapper;
    private final OImageFlowMapper imageFlowMapper;
    private final MediaGenerationService mediaGenerationService;
    private final Long projectId;
    private final Integer scriptId;
    private final String imageModel;

    public ProductionAgentTools(OAssetsMapper assetsMapper, OScriptAssetsMapper scriptAssetsMapper,
                                OStoryboardMapper storyboardMapper, OImageFlowMapper imageFlowMapper,
                                MediaGenerationService mediaGenerationService,
                                Long projectId, Integer scriptId, String imageModel) {
        this.assetsMapper = assetsMapper;
        this.scriptAssetsMapper = scriptAssetsMapper;
        this.storyboardMapper = storyboardMapper;
        this.imageFlowMapper = imageFlowMapper;
        this.mediaGenerationService = mediaGenerationService;
        this.projectId = projectId;
        this.scriptId = scriptId;
        this.imageModel = imageModel;
    }

    @Tool(description = "获取图片流程数据")
    public String getFlowData(@ToolParam(description = "流程 id") Integer flowId) {
        log.info("[tool] getFlowData {}", flowId);
        OImageFlow flow = imageFlowMapper.selectById(flowId);
        return flow != null && flow.getFlowData() != null ? flow.getFlowData() : "无数据";
    }

    @Tool(description = "新增或更新衍生资产（id 为空则新增）")
    public String addDeriveAsset(
            @ToolParam(description = "关联的资产 ID") Integer assetsId,
            @ToolParam(description = "衍生资产 ID，新增时传 null") Integer id,
            @ToolParam(description = "衍生资产名称") String name,
            @ToolParam(description = "衍生资产描述") String desc) {
        log.info("[tool] addDeriveAsset name={} id={}", name, id);
        OAssets parent = assetsMapper.selectById(assetsId);
        if (parent == null) return "关联的资产不存在";

        if (id != null) {
            OAssets asset = assetsMapper.selectById(id);
            if (asset == null) return "衍生资产不存在";
            asset.setAssetsId(assetsId);
            asset.setName(name);
            asset.setType(parent.getType());
            asset.setDescribe(desc);
            assetsMapper.updateById(asset);
            return "已更新衍生资产，ID: " + id;
        } else {
            OAssets asset = new OAssets();
            asset.setAssetsId(assetsId);
            asset.setProjectId(projectId.intValue());
            asset.setName(name);
            asset.setType(parent.getType());
            asset.setDescribe(desc);
            asset.setStartTime(System.currentTimeMillis());
            assetsMapper.insert(asset);

            OScriptAssets sa = new OScriptAssets();
            sa.setScriptId(scriptId);
            sa.setAssetId(asset.getId());
            scriptAssetsMapper.insert(sa);
            return "已新增衍生资产，ID: " + asset.getId();
        }
    }

    @Tool(description = "删除衍生资产")
    public String delDeriveAsset(
            @ToolParam(description = "衍生资产 ID") Integer id) {
        log.info("[tool] delDeriveAsset {}", id);
        assetsMapper.deleteById(id);
        scriptAssetsMapper.delete(new LambdaQueryWrapper<OScriptAssets>()
                .eq(OScriptAssets::getScriptId, scriptId)
                .eq(OScriptAssets::getAssetId, id));
        return "已删除衍生资产，ID: " + id;
    }

    @Tool(description = "生成衍生资产图片")
    public String generateDeriveAsset(
            @ToolParam(description = "需要生成的衍生资产 ID 列表") List<Integer> ids) {
        log.info("[tool] generateDeriveAsset {}", ids);
        if (ids == null || ids.isEmpty()) return "无可生成的资产";
        int success = 0;
        for (Integer id : ids) {
            OAssets asset = assetsMapper.selectById(id);
            if (asset == null) continue;
            try {
                String url = mediaGenerationService.generateImage(imageModel, asset.getPrompt(), "1024x1024");
                log.info("衍生资产 {} 生成图片: {}", id, url);
                success++;
            } catch (Exception e) {
                log.error("衍生资产 {} 生成失败: {}", id, e.getMessage());
            }
        }
        return "已生成 " + success + "/" + ids.size() + " 个衍生资产图片";
    }

    @Tool(description = "生成分镜图片，传入真实的分镜 ID 列表，支持批量")
    public String generateStoryboard(
            @ToolParam(description = "分镜 ID 列表") List<Integer> ids) {
        log.info("[tool] generateStoryboard {}", ids);
        if (ids == null || ids.isEmpty()) return "无可生成的分镜";
        for (Integer id : ids) {
            OStoryboard sb = storyboardMapper.selectById(id);
            if (sb != null) {
                sb.setState("生成中");
                sb.setShouldGenerateImage(1);
                storyboardMapper.updateById(sb);
            }
        }
        return "已提交 " + ids.size() + " 个分镜的图片生成任务";
    }
}
