package com.toonflow.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.entity.*;
import com.toonflow.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DbInitConfig implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final OUserMapper userMapper;
    private final OSettingMapper settingMapper;
    private final OAgentDeployMapper agentDeployMapper;
    private final OPromptMapper promptMapper;
    private final OVendorConfigMapper vendorConfigMapper;

    @Value("${toonflow.data-dir}")
    private String dataDir;

    @Override
    public void run(ApplicationArguments args) {
        ensureDirectories();
        copyDefaultSkills();
        initTables();
        initDefaultData();
    }

    private void copyDefaultSkills() {
        Path skillsTarget = Paths.get(dataDir, "skills");
        // 已有内容则跳过
        try {
            if (Files.exists(skillsTarget) && Files.list(skillsTarget).findAny().isPresent()) return;
        } catch (IOException ignored) {}
        URL resource = getClass().getClassLoader().getResource("default-data/skills");
        if (resource == null) { log.warn("default-data/skills 资源包不存在，跳过初始化技能目录"); return; }
        try {
            Path src = Paths.get(resource.toURI());
            Files.walkFileTree(src, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(skillsTarget.resolve(src.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Path dest = skillsTarget.resolve(src.relativize(file));
                    if (!Files.exists(dest)) Files.copy(file, dest);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.info("默认技能文件已复制到 {}", skillsTarget);
        } catch (URISyntaxException | IOException e) {
            log.warn("复制默认技能文件失败: {}", e.getMessage());
        }
    }

    private void ensureDirectories() {
        for (String dir : new String[]{"oss", "assets", "skills", "web", "vendor"}) {
            new File(dataDir + File.separator + dir).mkdirs();
        }
    }

    /** 检测并迁移旧 INTEGER 主键表到 TEXT 主键 */
    private void migrateSchema() {
        String[] tables = {
            "o_novel","o_script","o_assets","o_storyboard","o_image","o_video",
            "o_videoTrack","o_artStyle","o_agentDeploy","o_agentWorkData","o_prompt",
            "o_modelPrompt","o_tasks","o_event","o_eventChapter","o_outline",
            "o_outlineNovel","o_imageFlow","o_project"
        };
        for (String table : tables) {
            try {
                var rows = jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")");
                for (var row : rows) {
                    if ("id".equals(row.get("name")) && "INTEGER".equalsIgnoreCase(String.valueOf(row.get("type")))) {
                        jdbcTemplate.execute("DROP TABLE IF EXISTS " + table);
                        log.info("已删除旧 INTEGER 主键表: {}，将重新建表", table);
                        break;
                    }
                }
            } catch (Exception e) {
                log.debug("migrateSchema check {} skipped: {}", table, e.getMessage());
            }
        }
        // 修复关联表 INTEGER 列
        try {
            var rows = jdbcTemplate.queryForList("PRAGMA table_info(o_assetsRole2Audio)");
            for (var row : rows) {
                if ("assetsRoleId".equals(row.get("name")) && "INTEGER".equalsIgnoreCase(String.valueOf(row.get("type")))) {
                    jdbcTemplate.execute("DROP TABLE IF EXISTS o_assetsRole2Audio");
                    log.info("已删除旧 INTEGER 列表: o_assetsRole2Audio");
                    break;
                }
            }
        } catch (Exception ignored) {}
    }

    private void initTables() {
        migrateSchema();
        // 用户表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_user (
                id INTEGER PRIMARY KEY,
                name TEXT,
                password TEXT
            )""");
        // 项目表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_project (
                id TEXT PRIMARY KEY,
                projectType TEXT,
                imageModel TEXT,
                imageQuality TEXT,
                videoModel TEXT,
                name TEXT,
                intro TEXT,
                type TEXT,
                artStyle TEXT,
                directorManual TEXT,
                mode TEXT,
                videoRatio TEXT,
                createTime INTEGER,
                userId INTEGER
            )""");
        // 原文表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_novel (
                id TEXT PRIMARY KEY,
                projectId TEXT,
                chapterIndex INTEGER,
                reel TEXT,
                chapter TEXT,
                chapterData TEXT,
                createTime INTEGER,
                eventState INTEGER,
                event TEXT,
                errorReason TEXT
            )""");
        // 剧本表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_script (
                id TEXT PRIMARY KEY,
                name TEXT,
                content TEXT,
                projectId TEXT,
                createTime INTEGER,
                extractState INTEGER,
                errorReason TEXT
            )""");
        // 素材表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_assets (
                id TEXT PRIMARY KEY,
                projectId TEXT,
                scriptId TEXT,
                flowId TEXT,
                assetsId TEXT,
                imageId TEXT,
                name TEXT,
                type TEXT,
                describe TEXT,
                prompt TEXT,
                promptState TEXT,
                promptErrorReason TEXT,
                remark TEXT,
                startTime INTEGER,
                audioBindState INTEGER
            )""");
        // 分镜表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_storyboard (
                id TEXT PRIMARY KEY,
                projectId TEXT,
                scriptId TEXT,
                flowId TEXT,
                trackId TEXT,
                idx INTEGER,
                prompt TEXT,
                state TEXT,
                filePath TEXT,
                track TEXT,
                duration TEXT,
                reason TEXT,
                videoDesc TEXT,
                shouldGenerateImage INTEGER,
                createTime INTEGER
            )""");
        // 图片表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_image (
                id TEXT PRIMARY KEY,
                assetsId TEXT,
                filePath TEXT,
                state TEXT,
                model TEXT,
                resolution TEXT,
                type TEXT,
                errorReason TEXT
            )""");
        // 视频表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_video (
                id TEXT PRIMARY KEY,
                projectId TEXT,
                scriptId TEXT,
                videoTrackId TEXT,
                filePath TEXT,
                state TEXT,
                time INTEGER,
                errorReason TEXT
            )""");
        // 视频轨道表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_videoTrack (
                id TEXT PRIMARY KEY,
                projectId TEXT,
                scriptId TEXT,
                videoId TEXT,
                selectVideoId TEXT,
                prompt TEXT,
                state TEXT,
                reason TEXT,
                duration INTEGER
            )""");
        // 风格表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_artStyle (
                id TEXT PRIMARY KEY,
                name TEXT,
                fileUrl TEXT,
                label TEXT,
                prompt TEXT
            )""");
        // Agent配置表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_agentDeploy (
                id TEXT PRIMARY KEY,
                key TEXT,
                model TEXT,
                modelName TEXT,
                vendorId TEXT,
                name TEXT,
                desc TEXT,
                type TEXT,
                temperature REAL,
                topP REAL,
                maxOutputTokens INTEGER,
                disabled INTEGER
            )""");
        // Agent工作数据表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_agentWorkData (
                id TEXT PRIMARY KEY,
                projectId TEXT,
                episodesId TEXT,
                key TEXT,
                data TEXT,
                createTime INTEGER,
                updateTime INTEGER
            )""");
        // 提示词表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_prompt (
                id TEXT PRIMARY KEY,
                name TEXT,
                type TEXT,
                data TEXT,
                useData TEXT
            )""");
        // 模型提示词绑定表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_modelPrompt (
                id TEXT PRIMARY KEY,
                vendorId TEXT,
                model TEXT,
                prompt TEXT,
                path TEXT,
                fileName TEXT
            )""");
        // 配置表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_setting (
                key TEXT PRIMARY KEY,
                value TEXT
            )""");
        // 供应商配置表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_vendorConfig (
                id TEXT PRIMARY KEY,
                enable INTEGER,
                inputValues TEXT,
                models TEXT
            )""");
        // 任务表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_tasks (
                id TEXT PRIMARY KEY,
                projectId TEXT,
                state TEXT,
                model TEXT,
                taskClass TEXT,
                describe TEXT,
                reason TEXT,
                relatedObjects TEXT,
                startTime INTEGER
            )""");
        // 事件表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_event (
                id TEXT PRIMARY KEY,
                name TEXT,
                detail TEXT,
                createTime INTEGER
            )""");
        // 事件章节表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_eventChapter (
                id TEXT PRIMARY KEY,
                eventId TEXT,
                novelId TEXT
            )""");
        // 大纲表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_outline (
                id TEXT PRIMARY KEY,
                projectId TEXT,
                episode INTEGER,
                data TEXT
            )""");
        // 大纲-原文关联表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_outlineNovel (
                id TEXT PRIMARY KEY,
                outlineId TEXT,
                novelId TEXT
            )""");
        // 图片流程表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_imageFlow (
                id TEXT PRIMARY KEY,
                flowData TEXT
            )""");
        // 素材-分镜关联表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_assets2Storyboard (
                assetId TEXT,
                storyboardId TEXT
            )""");
        // 剧本-素材关联表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_scriptAssets (
                scriptId TEXT,
                assetId TEXT
            )""");
        // 角色-音频关联表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_assetsRole2Audio (
                assetsRoleId TEXT,
                assetsAudioId TEXT
            )""");
        // 技能列表表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_skillList (
                id TEXT PRIMARY KEY,
                name TEXT,
                description TEXT,
                type TEXT,
                path TEXT,
                md5 TEXT,
                state INTEGER,
                embedding TEXT,
                createTime INTEGER,
                updateTime INTEGER
            )""");
        // 技能归属表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_skillAttribution (
                skillId TEXT,
                attribution TEXT
            )""");
        // 记忆表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS memories (
                id TEXT PRIMARY KEY,
                isolationKey TEXT,
                type TEXT,
                role TEXT,
                name TEXT,
                content TEXT,
                embedding TEXT,
                relatedMessageIds TEXT,
                summarized INTEGER,
                createTime INTEGER
            )""");

        log.info("数据库表初始化完成");
    }

    private void initDefaultData() {
        // 初始化管理员账号
        if (userMapper.selectCount(new LambdaQueryWrapper<OUser>().eq(OUser::getId, 1)) == 0) {
            OUser admin = new OUser();
            admin.setId(1);
            admin.setName("admin");
            admin.setPassword("admin123");
            userMapper.insert(admin);
        }

        // 初始化 tokenKey
        if (settingMapper.selectById("tokenKey") == null) {
            OSetting tokenKey = new OSetting();
            tokenKey.setKey("tokenKey");
            tokenKey.setValue(UUID.randomUUID().toString().replace("-", ""));
            settingMapper.insert(tokenKey);
        }

        // 初始化 agentUseMode
        if (settingMapper.selectById("agentUseMode") == null) {
            OSetting agentMode = new OSetting();
            agentMode.setKey("agentUseMode");
            agentMode.setValue("0");
            settingMapper.insert(agentMode);
        }

        // 初始化 Agent 部署配置（含完整 desc 与 temperature）
        initAgentDeploy("scriptAgent", "剧本Agent", "用于读取原文生成故事骨架、改编策略，建议使用具备强大文本理解和生成能力的模型", null, null);
        initAgentDeploy("productionAgent", "生产Agent", "对工作流进行调度和管理，建议使用具备较强的逻辑推理和任务管理能力的模型", null, null);
        initAgentDeploy("universalAi", "通用AI", "用于小说事件提取、资产提示词生成、台词提取等边缘功能，建议使用具备较强文本处理能力的模型", null, null);
        initAgentDeploy("ttsDubbing", "TTS配音", "根据剧本内容生成角色配音，支持多种声音风格和情绪", null, true);
        initAgentDeploy("scriptAgent:decisionAgent", "剧本Agent:决策层", "决策层", 1.0, null);
        initAgentDeploy("scriptAgent:supervisionAgent", "剧本Agent:监督层", "监督层", 1.0, null);
        initAgentDeploy("scriptAgent:storySkeletonAgent", "剧本Agent:故事骨架", "故事骨架生成", 1.0, null);
        initAgentDeploy("scriptAgent:adaptationStrategyAgent", "剧本Agent:改编策略", "改编策略生成", 1.0, null);
        initAgentDeploy("scriptAgent:scriptAgent", "剧本Agent:剧本生成", "剧本生成", 1.0, null);
        initAgentDeploy("productionAgent:decisionAgent", "生产Agent:决策层", "决策层", 1.0, null);
        initAgentDeploy("productionAgent:supervisionAgent", "生产Agent:监督层", "监督层", 1.0, null);
        initAgentDeploy("productionAgent:deriveAssetsAgent", "生产Agent:衍生资产", "衍生资产", 1.0, null);
        initAgentDeploy("productionAgent:generateAssetsAgent", "生产Agent:生成资产", "生成资产", 1.0, null);
        initAgentDeploy("productionAgent:directorPlanAgent", "生产Agent:导演规划", "导演规划", 1.0, null);
        initAgentDeploy("productionAgent:storyboardGenAgent", "生产Agent:分镜生成", "分镜生成", 1.0, null);
        initAgentDeploy("productionAgent:storyboardPanelAgent", "生产Agent:分镜面板", "分镜面板生成", 1.0, null);
        initAgentDeploy("productionAgent:storyboardTableAgent", "生产Agent:分镜表格", "分镜表格生成", 1.0, null);

        // 初始化供应商配置
        for (String vid : new String[]{"toonflow","deepseek","atlascloud","volcengine","minimax","openai","klingai","vidu"}) {
            initVendorConfig(vid);
        }

        // 初始化提示词
        initPrompt("事件提取", "eventExtraction");
        initPrompt("剧本资产提取", "scriptAssetExtraction");
        initPrompt("视频提示词生成", "videoPromptGeneration");
        initPrompt("音色绑定", "audioBindPrompt");

        // 初始化记忆配置
        initSetting("messagesPerSummary", "10");
        initSetting("shortTermLimit", "5");
        initSetting("summaryMaxLength", "500");
        initSetting("summaryLimit", "10");
        initSetting("ragLimit", "3");
        initSetting("deepRetrieveSummaryLimit", "5");
        initSetting("modelOnnxFile", "[\"all-MiniLM-L6-v2\", \"onnx\", \"model_fp16.onnx\"]");
        initSetting("modelDtype", "fp16");
        initSetting("switchAiDevTool", "0");

        log.info("默认数据初始化完成");
    }

    private void initAgentDeploy(String key, String name, String desc, Double temperature, Boolean disabled) {
        if (agentDeployMapper.selectCount(
                new LambdaQueryWrapper<OAgentDeploy>().eq(OAgentDeploy::getKey, key)) == 0) {
            OAgentDeploy deploy = new OAgentDeploy();
            deploy.setKey(key);
            deploy.setName(name);
            deploy.setDesc(desc);
            deploy.setType("text");
            deploy.setTemperature(temperature);
            deploy.setDisabled(disabled != null && disabled);
            agentDeployMapper.insert(deploy);
        }
    }

    private void initVendorConfig(String id) {
        String defaultModels = loadResource("default-data/vendor-models/" + id + ".json");
        if (defaultModels.isEmpty()) defaultModels = "[]";
        OVendorConfig existing = vendorConfigMapper.selectById(id);
        if (existing == null) {
            OVendorConfig config = new OVendorConfig();
            config.setId(id);
            config.setEnable("toonflow".equals(id) ? 1 : 0);
            config.setInputValues("{}");
            config.setModels(defaultModels);
            vendorConfigMapper.insert(config);
        } else if (existing.getModels() == null || "[]".equals(existing.getModels())
                || !defaultModels.equals(existing.getModels())) {
            // 已有记录但 models 为空或与内置默认不一致时，刷新为内置默认模型列表
            // （保留用户的 enable / inputValues；模型定义以内置资源为准，含 durationResolutionMap 等完整字段）
            existing.setModels(defaultModels);
            vendorConfigMapper.updateById(existing);
        }
    }

    private void initPrompt(String name, String type) {
        if (promptMapper.selectCount(new LambdaQueryWrapper<OPrompt>().eq(OPrompt::getType, type)) == 0) {
            String data = loadResource("default-data/prompts/" + type + ".txt");
            OPrompt prompt = new OPrompt();
            prompt.setName(name);
            prompt.setType(type);
            prompt.setData(data);
            promptMapper.insert(prompt);
        }
    }

    private void initSetting(String key, String value) {
        if (settingMapper.selectById(key) == null) {
            OSetting s = new OSetting();
            s.setKey(key);
            s.setValue(value);
            settingMapper.insert(s);
        }
    }

    private String loadResource(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) return "";
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("加载资源文件失败: {}", path);
            return "";
        }
    }
}
