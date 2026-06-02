package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_agentWorkData")
public class OAgentWorkData {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String projectId;
    private String episodesId;
    private String key;
    private String data;
    private Long createTime;
    private Long updateTime;
}
