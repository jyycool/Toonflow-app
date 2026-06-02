package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_novel")
public class ONovel {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String projectId;
    private Integer chapterIndex;
    private String reel;
    private String chapter;
    private String chapterData;
    private Long createTime;
    private Integer eventState;
    private String event;
    private String errorReason;
}
