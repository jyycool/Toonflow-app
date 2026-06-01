package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_skillAttribution")
public class OSkillAttribution {
    private String skillId;
    private String attribution;
}
