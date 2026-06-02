package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_script")
public class OScript {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String name;
    private String content;
    private String projectId;
    private Long createTime;
    private Integer extractState;
    private String errorReason;
}
