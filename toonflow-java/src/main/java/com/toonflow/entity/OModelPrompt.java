package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_modelPrompt")
public class OModelPrompt {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String vendorId;
    private String model;
    private String prompt;
    private String path;
    private String fileName;
}
