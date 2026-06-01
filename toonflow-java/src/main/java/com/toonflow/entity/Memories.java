package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("memories")
public class Memories {
    @TableId
    private String id;
    private String isolationKey;
    private String type;
    private String role;
    private String name;
    private String content;
    private String embedding;
    private String relatedMessageIds;
    private Integer summarized;
    private Long createTime;
}
