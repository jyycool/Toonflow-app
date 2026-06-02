package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_assets2Storyboard")
public class OAssets2Storyboard {
    private String assetId;
    private String storyboardId;
}
