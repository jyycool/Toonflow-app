package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_storyboard")
public class OStoryboard {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String projectId;
    private String scriptId;
    private String flowId;
    private String trackId;
    // DB column name is idx (index is a reserved keyword in SQLite)
    private Integer idx;
    private String prompt;
    private String state;
    private String filePath;
    private String track;
    private String duration;
    private String reason;
    private String videoDesc;
    private Integer shouldGenerateImage;
    private Long createTime;
}
