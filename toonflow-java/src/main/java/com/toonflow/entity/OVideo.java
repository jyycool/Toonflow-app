package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_video")
public class OVideo {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String projectId;
    private String scriptId;
    private String videoTrackId;
    private String filePath;
    private String state;
    private Long time;
    private String errorReason;
}
