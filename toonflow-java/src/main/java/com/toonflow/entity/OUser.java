package com.toonflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("o_user")
public class OUser {
    @TableId(type = IdType.INPUT)
    private Integer id;
    private String name;
    private String password;
}
