# Toonflow Java API 参考手册（前端对接）

> 基础地址：`http://localhost:10588`
> 共 **120+** 个端点，覆盖原 TypeScript 项目的全部主要功能。

## 通用约定

### 认证
除登录外，所有请求需在 Header 携带 Token：
```
Authorization: Bearer <token>
```
也可用 query 参数 `?token=<token>`（用于 WebSocket / 静态资源）。

### 统一响应格式
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": { }
}
```
失败时 `success=false`，`code` 为错误码，`message` 为错误描述。

### 静态资源
| 路径 | 说明 |
|------|------|
| `/oss/**` | 生成的图片 |
| `/assets/**` | 上传的素材 |
| `/skills/**` | 技能图片 |

---

## 1. 登录 `/api/login`

| 方法 | 路径 | 说明 | 请求体 |
|------|------|------|--------|
| POST | `/login` | 登录 | `{ username, password }` |

返回：`{ token, name, id }`，默认账号 `admin` / `admin123`。

---

## 2. 项目 `/api/project`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/getProject` | 项目列表 |
| POST | `/addProject` | 新增项目 `{ projectType, name, intro, type, artStyle, directorManual, videoRatio, imageModel, videoModel, imageQuality, mode }` |
| POST | `/editProject` | 编辑项目 `{ id, ...可选字段 }` |
| POST | `/delProject` | 删除 `{ id }` |

通用项目接口（`/api`）：
| GET | `/general/getSingleProject?id=` | 单项目详情 |
| POST | `/general/updateProject` | 更新项目 |
| GET | `/general/generalStatistics?projectId=` | 项目统计（各类数量） |

---

## 3. 原文 `/api/novel`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/addNovel` | 新增原文 `{ projectId, data:[{index,reel,chapter,chapterData}] }`，自动触发事件清洗 |
| GET | `/getNovel?projectId=` | 章节列表 |
| POST | `/getNovelData` | 全量原文 `{ projectId }` |
| GET | `/getNovelIndex?projectId=` | 章节目录（精简） |
| GET | `/getNovelEventState?projectId=` | 事件生成状态 |
| POST | `/updateNovel` | 更新章节 |
| POST | `/delNovel` | 删除 `{ id }` |
| POST | `/batchDeleteNovel` | 批量删除 `{ ids }` |
| POST | `/event/generateEvents` | 生成章节事件 `{ projectId, novelIds, concurrentCount? }` |

---

## 4. 剧本 `/api/script`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/addScript` | 新增剧本 `{ name, content, projectId, assets:[] }` |
| POST | `/batchAddScript` | 批量新增 |
| GET | `/getScrptApi?projectId=` | 剧本列表 |
| POST | `/updateScript` | 更新 |
| POST | `/delScript` | 删除 `{ id }` |
| POST | `/exportScript` | 导出 zip `{ id:[] }`（返回二进制 zip 流） |
| POST | `/extractAssets` | 提取剧本资产 `{ scriptIds:[] }` |

---

## 5. 素材 `/api/assets`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/addAssets` | 新增素材 |
| GET | `/getAssetsApi?projectId=&scriptId=` | 素材列表 |
| POST | `/updateAssets` | 更新 |
| POST | `/saveAssets` | 保存（无 id 则新增） |
| POST | `/delAssets` | 删除 `{ id }` |
| POST | `/batchDelete` | 批量删除 `{ ids }` |
| GET | `/getImage?id=` | 获取图片记录 |
| GET | `/pollingImageAssets?ids=` | 轮询图片状态 |
| GET | `/pollingPromptAssets?ids=` | 轮询提示词状态 |

角落场景（`/api/cornerScape`）：
| POST | `/getAllAssets` | 全部素材联查图片 `{ projectId, type?:[] }` |

---

## 6. 素材/图片生成 `/api`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/assetsGenerate/generateAssets` | 单图生成 `{ projectId, prompt }` |
| POST | `/assetsGenerate/batchGenerateImageAssets` | 批量素材图 `{ assetIds, projectId }` |
| POST | `/production/storyboard/batchGenerateImage` | 批量分镜图 `{ storyboardIds, projectId, scriptId, compulsory? }` |

> 均为异步：立即返回，前端轮询对应状态接口。

---

## 7. 分镜 `/api/production/storyboard`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/addStoryboard` | 新增分镜 |
| POST | `/batchAddStoryboardInfo` | 批量新增 `{ data:[], scriptId, projectId }` |
| GET | `/getStoryboardData?projectId=&scriptId=` | 分镜列表 |
| POST | `/editStoryboardInfo` | 编辑 |
| POST | `/updateStoryboardUrl` | 更新图片地址 |
| POST | `/batchDelete` | 批量删除 `{ ids }` |
| POST | `/removeFrame` | 删除单帧 `{ id }` |
| GET | `/pollingImage?ids=` | 轮询图片状态 |
| POST | `/previewImage` | 预览图片 `{ storyboardIds }` |
| POST | `/downPreviewImage` | 下载预览 |

---

## 8. 制作工作台 `/api/production`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/getFlowData?id=` | 获取流程数据 |
| POST | `/saveFlowData` | 保存流程 |
| GET | `/getStoryboardData?projectId=` | 制作侧分镜 |
| GET | `/workbench/getVideoList?projectId=` | 视频列表 |
| GET | `/workbench/getGenerateData?projectId=` | 轨道+视频汇总 |
| POST | `/workbench/addTrack` | 新增轨道 |
| POST | `/workbench/deleteTrack` | 删除轨道 `{ id }` |
| POST | `/workbench/selectVideo` | 选定视频 `{ trackId, videoId }` |
| POST | `/workbench/delVideo` | 删除视频 `{ id }` |
| POST | `/workbench/updateVideoPrompt` | 更新提示词 |
| POST | `/workbench/updateVideoDuration` | 更新时长 |
| POST | `/workbench/generateVideo` | 生成视频 `{ projectId, scriptId, videoTrackId, prompt }`（异步） |
| POST | `/workbench/batchGenerateVideo` | 批量生成 `{ tasks:[], projectId }` |
| POST | `/workbench/checkVideoStateList` | 轮询视频状态 `{ videoIds }` |
| POST | `/workbench/getFileUrl` | 取文件地址 `{ items:[{id,sources}] }` |

图片编辑：
| GET | `/editImage/getImageFlow?id=` | 获取图片流程 |
| POST | `/editImage/saveImageFlow` | 保存 |
| POST | `/editImage/updateImageFlow` | 更新 |
| POST | `/editImage/getImageDefaultModle` | 默认模型 `{ projectId }` |
| POST | `/editImage/generateFlowImage` | 流程图生成 `{ projectId, prompt, model, ratio }` |

---

## 9. 风格 `/api/artStyle`

| GET | `/getArtStyle` | 风格列表 |
| POST | `/addArtStyle` | 新增 |
| POST | `/editArtStyle` | 编辑 |

---

## 10. 模型选择 `/api/modelSelect`

| GET | `/getModelList?type=` | 可用模型列表 |
| GET | `/getModelDetail?modelName=` | 模型详情（`vendorId:modelId`） |

---

## 11. 设置 `/api/setting`

### 供应商配置 `/vendorConfig`
| GET | `/getVendorList` | 供应商列表 |
| POST | `/addVendor` | 新增 |
| POST | `/enableVendor` | 启用/禁用 `{ id, enable }` |
| POST | `/deleteVendor` | 删除 `{ id }` |
| POST | `/updateVendorInputs` | 更新参数 |
| POST | `/addVendorModel` / `/delVendorModel` / `/upVendorModel` | 模型增删改 |
| POST | `/modelTest/textTest` / `/imageTest` / `/videoTest` | 模型连通性测试 `{ modelName }` |

### Agent 部署 `/agentDeploy`
| GET | `/getAgentDeploy` | 部署列表 |
| POST | `/deployAgentModel` | 部署模型 |
| GET | `/getAgentUseMode` | 使用模式（0简易/1高级） |
| POST | `/updateUseMode` | 更新模式 `{ mode }` |
| POST | `/agentSetKey` | 设置 key |

### 提示词 `/promptManage`、模型映射 `/modelMap`
| GET | `/promptManage/getPrompt?type=` | 提示词列表 |
| POST | `/promptManage/updatePrompt` | 更新 |
| GET | `/modelMap/getPromptList` | 模型提示词文件列表 |
| POST | `/modelMap/savePrompt` | 保存 `{ name, data, type }` |
| POST | `/modelMap/bindingPrompt` | 绑定 `{ vendorId, model, path, fileName }` |
| GET | `/modelMap/getImageAndVideoModel` | 图片视频模型映射 |

### 记忆配置 `/memoryConfig`
| GET | `/getMemory` | 读取记忆参数 |
| POST | `/sureMemory` | 保存参数 |
| POST | `/delAllMemory` | 清空记忆 |

### 登录/开发/数据
| GET | `/loginConfig/getUser` | 当前用户 |
| POST | `/loginConfig/updateUserPwd` | 改密码 `{ password }` |
| GET | `/dev/getSwitchAiDevTool` | 开发工具开关 |
| POST | `/dev/updateSwitchAiDevTool` | 更新开关 |
| GET | `/dbConfig/exportData` | 导出数据库（下载 .db） |
| POST | `/dbConfig/importData` | 导入（multipart file） |
| POST | `/dbConfig/clearTable` | 清表 `{ table }` |
| POST | `/dbConfig/clearData` | 清全部业务数据 |
| GET | `/dbConfig/dbInfo` | 数据库统计 |

### 技能管理 `/skillManagement`
| POST | `/getSkillList` | 技能列表 |
| GET | `/getSkillContent?path=` | 读取技能 |
| POST | `/saveSkillContent` | 保存 `{ path, content }` |

### 关于
| POST | `/about/checkUpdate` | 检查更新 `{ url? }` |

---

## 12. 任务 `/api/task`

| GET/POST | `/getTaskApi?projectId=` | 任务列表 |
| POST | `/getTaskCategories` | 任务分类 |
| POST | `/taskDetails` | 任务详情 `{ id }` |

---

## 13. 文件 `/api`

| POST | `/assets/uploadClip` | 上传素材（multipart `file`） |
| POST | `/production/editImage/uploadImage` | 上传图片 |
| GET | `/common/getBigImage?url=` | 大图地址 |

---

## 14. Agent（AI 智能体）

### REST `/api/agents`
| GET | `/getMemory?isolationKey=` | 获取记忆 |
| POST | `/clearMemory` | 清除记忆 `{ isolationKey }` |
| GET | `/scriptAgent/getPlanData?projectId=&episodesId=&key=` | 获取工作区数据 |
| POST | `/scriptAgent/setPlanData` | 保存工作区数据 |
| GET | `/stream?agentType=&prompt=` | **SSE 流式生成**（text/event-stream） |

### WebSocket（STOMP）
连接：`ws://localhost:10588/ws`（SockJS）

| 发送目标 | Payload | 说明 |
|----------|---------|------|
| `/app/scriptAgent` | `{ sessionId, isolationKey, projectId, message }` | 剧本 Agent（带记忆+工具调用） |
| `/app/productionAgent` | `{ sessionId, isolationKey, projectId, message }` | 制作 Agent |
| `/app/agent` | `{ sessionId, agentType, message }` | 通用 Agent |

订阅：`/topic/agent/{sessionId}`，消息类型：
```json
{ "type": "chunk", "content": "..." }   // 流式片段
{ "type": "agentStart", "name": "..." } // 子Agent启动
{ "type": "done" }                      // 完成
{ "type": "error", "message": "..." }   // 错误
```

---

## Agent 工具调用（大模型自主调用）

剧本 Agent 可调用：查章节事件、查原文、读剧本、存剧本。
制作 Agent 可调用：增删衍生资产、生成素材图、生成分镜。

这些工具由大模型根据对话自主决策触发，无需前端干预。
