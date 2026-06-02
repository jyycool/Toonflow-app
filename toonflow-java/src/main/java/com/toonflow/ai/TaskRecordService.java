package com.toonflow.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.entity.OTasks;
import com.toonflow.mapper.OTasksMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 任务记录服务
 * 对应原项目 src/utils/taskRecord.ts
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRecordService {

    private final OTasksMapper tasksMapper;
    private final ObjectMapper objectMapper;

    public static final String STATE_RUNNING = "进行中";
    public static final String STATE_DONE = "已完成";
    public static final String STATE_FAILED = "生成失败";

    /**
     * 创建任务记录，返回任务 id
     */
    public String start(String projectId, String taskClass, String modelName, String describe, Object content) {
        OTasks task = new OTasks();
        task.setProjectId(projectId);
        task.setTaskClass(taskClass);
        task.setModel(modelName);
        task.setDescribe(describe != null ? describe : "");
        task.setState(STATE_RUNNING);
        task.setStartTime(System.currentTimeMillis());
        task.setRelatedObjects(serializeContent(content));
        tasksMapper.insert(task);
        return task.getId() != null ? task.getId() : null;
    }

    /**
     * 标记任务成功
     */
    public void done(String taskId) {
        OTasks task = tasksMapper.selectById(taskId);
        if (task != null) {
            task.setState(STATE_DONE);
            task.setReason(null);
            tasksMapper.updateById(task);
        }
    }

    /**
     * 标记任务失败
     */
    public void fail(String taskId, String reason) {
        OTasks task = tasksMapper.selectById(taskId);
        if (task != null) {
            task.setState(STATE_FAILED);
            task.setReason(reason != null ? reason : "");
            tasksMapper.updateById(task);
        }
    }

    private String serializeContent(Object content) {
        if (content == null) return null;
        if (content instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            return content.toString();
        }
    }
}
