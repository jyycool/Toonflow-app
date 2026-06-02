package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_event")
public class OEvent {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String name;
    private String detail;
    private Long createTime;
}
