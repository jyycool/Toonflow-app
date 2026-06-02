package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_videoTrack")
public class OVideoTrack {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String projectId;
    private String scriptId;
    private String videoId;
    private String selectVideoId;
    private String prompt;
    private String state;
    private String reason;
    private Integer duration;
}
