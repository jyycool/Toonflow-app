package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_vendorConfig")
public class OVendorConfig {
    @TableId
    private String id;
    private Integer enable;
    private String inputValues;
    private String models;
}
