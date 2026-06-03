package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.List;
import java.util.Map;

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

    @TableField(exist = false)
    private List<Map<String, Object>> relatedAssets;
}
