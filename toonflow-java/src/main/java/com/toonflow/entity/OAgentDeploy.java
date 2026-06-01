package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_agentDeploy")
public class OAgentDeploy {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String key;
    private String model;
    private String modelName;
    private String vendorId;
    private String name;
    private String desc;
    private String type;
    private Double temperature;
    private Double topP;
    private Integer maxOutputTokens;
    private Boolean disabled;
}
