package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_storyboard")
public class OStoryboard {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer projectId;
    private Integer scriptId;
    private Integer flowId;
    private Integer trackId;
    // 建表列名为 idx（规避 SQL 保留字 index）
    @TableField("idx")
    private Integer index;
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
