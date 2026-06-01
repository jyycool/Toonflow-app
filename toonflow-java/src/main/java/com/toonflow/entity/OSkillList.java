package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_skillList")
public class OSkillList {
    @TableId
    private String id;
    private String name;
    private String description;
    private String type;
    private String path;
    private String md5;
    private Integer state;
    private String embedding;
    private Long createTime;
    private Long updateTime;
}
