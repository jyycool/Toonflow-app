package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_image")
public class OImage {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String assetsId;
    private String filePath;
    private String state;
    private String model;
    private String resolution;
    private String type;
    private String errorReason;
}
