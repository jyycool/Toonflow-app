package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_project")
public class OProject {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String projectType;
    private String imageModel;
    private String imageQuality;
    private String videoModel;
    private String name;
    private String intro;
    private String type;
    private String artStyle;
    private String directorManual;
    private String mode;
    private String videoRatio;
    private Long createTime;
    private Integer userId;
}
