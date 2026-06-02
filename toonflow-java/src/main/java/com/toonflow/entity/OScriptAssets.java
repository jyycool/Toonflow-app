package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_scriptAssets")
public class OScriptAssets {
    private String scriptId;
    private String assetId;
}
