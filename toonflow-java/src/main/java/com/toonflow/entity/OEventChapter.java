package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_eventChapter")
public class OEventChapter {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String eventId;
    private String novelId;
}
