package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_artStyle")
public class OArtStyle {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String name;
    private String fileUrl;
    private String label;
    private String prompt;
}
