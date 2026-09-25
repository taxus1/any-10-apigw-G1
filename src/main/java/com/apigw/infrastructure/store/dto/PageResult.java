package com.apigw.infrastructure.store.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 对外分页返回对象（不可变 record）。
 * 带 totalPages，前端可直接渲染分页器，不用自己算。
 */
public record PageResult<T>(List<T> content, long total, int pageNum, int pageSize, int totalPages)
        implements Serializable {

    public PageResult(List<T> content, long total, int pageNum, int pageSize) {
        this(content,
                total,
                pageNum,
                pageSize,
                pageSize <= 0 ? 0 : (int) Math.ceil((double) total / pageSize));
    }
}
