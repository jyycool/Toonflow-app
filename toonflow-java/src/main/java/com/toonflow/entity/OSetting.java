package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_setting")
public class OSetting {
    @TableId
    private String key;
    private String value;
}
