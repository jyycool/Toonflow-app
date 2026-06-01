package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_prompt")
public class OPrompt {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String name;
    private String type;
    private String data;
    private String useData;
}
