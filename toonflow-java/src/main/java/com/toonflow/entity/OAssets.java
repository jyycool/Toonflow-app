package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_assets")
public class OAssets {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String projectId;
    private String scriptId;
    private String flowId;
    private String assetsId;
    private String imageId;
    private String name;
    private String type;
    private String describe;
    private String prompt;
    private String promptState;
    private String promptErrorReason;
    private String remark;
    private Long startTime;
    private Integer audioBindState;
}
