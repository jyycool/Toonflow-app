package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_outline")
public class OOutline {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String projectId;
    private Integer episode;
    private String data;
}
