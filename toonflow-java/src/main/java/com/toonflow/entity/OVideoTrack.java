package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_videoTrack")
public class OVideoTrack {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer projectId;
    private Integer scriptId;
    private Integer videoId;
    private Integer selectVideoId;
    private String prompt;
    private String state;
    private String reason;
    private Integer duration;
}
