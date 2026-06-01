package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_video")
public class OVideo {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer projectId;
    private Integer scriptId;
    private Integer videoTrackId;
    private String filePath;
    private String state;
    private Long time;
    private String errorReason;
}
