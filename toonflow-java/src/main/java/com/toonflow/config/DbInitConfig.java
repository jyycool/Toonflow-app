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

    private void initTables() {
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
                id INTEGER PRIMARY KEY,
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
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                projectId INTEGER,
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
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT,
                content TEXT,
                projectId INTEGER,
                createTime INTEGER,
                extractState INTEGER,
                errorReason TEXT
            )""");
        // 素材表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_assets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                projectId INTEGER,
                scriptId INTEGER,
                flowId INTEGER,
                assetsId INTEGER,
                imageId INTEGER,
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
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                projectId INTEGER,
                scriptId INTEGER,
                flowId INTEGER,
                trackId INTEGER,
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
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                assetsId INTEGER,
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
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                projectId INTEGER,
                scriptId INTEGER,
                videoTrackId INTEGER,
                filePath TEXT,
                state TEXT,
                time INTEGER,
                errorReason TEXT
            )""");
        // 视频轨道表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_videoTrack (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                projectId INTEGER,
                scriptId INTEGER,
                videoId INTEGER,
                selectVideoId INTEGER,
                prompt TEXT,
                state TEXT,
                reason TEXT,
                duration INTEGER
            )""");
        // 风格表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_artStyle (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT,
                fileUrl TEXT,
                label TEXT,
                prompt TEXT
            )""");
        // Agent配置表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_agentDeploy (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
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
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                projectId INTEGER,
                episodesId INTEGER,
                key TEXT,
                data TEXT,
                createTime INTEGER,
                updateTime INTEGER
            )""");
        // 提示词表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_prompt (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT,
                type TEXT,
                data TEXT,
                useData TEXT
            )""");
        // 模型提示词绑定表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_modelPrompt (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
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
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                projectId INTEGER,
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
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT,
                detail TEXT,
                createTime INTEGER
            )""");
        // 事件章节表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_eventChapter (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                eventId INTEGER,
                novelId INTEGER
            )""");
        // 大纲表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_outline (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                projectId INTEGER,
                episode INTEGER,
                data TEXT
            )""");
        // 大纲-原文关联表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_outlineNovel (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                outlineId INTEGER,
                novelId INTEGER
            )""");
        // 图片流程表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_imageFlow (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                flowData TEXT
            )""");
        // 素材-分镜关联表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_assets2Storyboard (
                assetId INTEGER,
                storyboardId INTEGER
            )""");
        // 剧本-素材关联表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_scriptAssets (
                scriptId INTEGER,
                assetId INTEGER
            )""");
        // 角色-音频关联表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS o_assetsRole2Audio (
                assetsRoleId INTEGER,
                assetsAudioId INTEGER
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

        // 初始化 Agent 部署配置
        initAgentDeploy("scriptAgent", "剧本Agent", "text");
        initAgentDeploy("productionAgent", "制作Agent", "text");
        initAgentDeploy("universalAi", "通用AI", "text");
        initAgentDeploy("scriptAgent:decisionAgent", "决策Agent", "text");
        initAgentDeploy("scriptAgent:supervisionAgent", "监督Agent", "text");
        initAgentDeploy("scriptAgent:storySkeletonAgent", "故事骨架Agent", "text");
        initAgentDeploy("scriptAgent:adaptationStrategyAgent", "改编策略Agent", "text");
        initAgentDeploy("scriptAgent:scriptAgent", "剧本撰写Agent", "text");
        initAgentDeploy("productionAgent:decisionAgent", "制作决策Agent", "text");
        initAgentDeploy("productionAgent:supervisionAgent", "制作监督Agent", "text");
        initAgentDeploy("productionAgent:deriveAssetsAgent", "素材提取Agent", "text");
        initAgentDeploy("productionAgent:generateAssetsAgent", "素材生成Agent", "text");
        initAgentDeploy("productionAgent:directorPlanAgent", "导演规划Agent", "text");
        initAgentDeploy("productionAgent:storyboardGenAgent", "分镜生成Agent", "text");
        initAgentDeploy("productionAgent:storyboardPanelAgent", "分镜面板Agent", "text");
        initAgentDeploy("productionAgent:storyboardTableAgent", "分镜表格Agent", "text");

        log.info("默认数据初始化完成");
    }

    private void initAgentDeploy(String key, String name, String type) {
        if (agentDeployMapper.selectCount(
                new LambdaQueryWrapper<OAgentDeploy>().eq(OAgentDeploy::getKey, key)) == 0) {
            OAgentDeploy deploy = new OAgentDeploy();
            deploy.setKey(key);
            deploy.setName(name);
            deploy.setType(type);
            deploy.setDisabled(false);
            agentDeployMapper.insert(deploy);
        }
    }
}
