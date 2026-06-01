package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_script")
public class OScript {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String name;
    private String content;
    private Integer projectId;
    private Long createTime;
    private Integer extractState;
    private String errorReason;
}
