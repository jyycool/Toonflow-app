package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_assetsRole2Audio")
public class OAssetsRole2Audio {
    private Integer assetsRoleId;
    private Integer assetsAudioId;
}
