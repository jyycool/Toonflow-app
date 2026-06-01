package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_agentWorkData")
public class OAgentWorkData {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer projectId;
    private Integer episodesId;
    private String key;
    private String data;
    private Long createTime;
    private Long updateTime;
}
