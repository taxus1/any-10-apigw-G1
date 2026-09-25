package com.apigw.common;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * 统一返回结构。全项目所有管理接口都返回它，不要各写各的。
 * code=0 表示成功，非 0 表示业务失败；前端按 code 判断，不要靠状态码猜。
 */
@Getter
@Setter
public class Result<T> implements Serializable {

    private int code;
    private String msg;
    private T data;

    public Result() {
    }

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(0);
        r.setMsg("success");
        r.setData(data);
        return r;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(String msg) {
        Result<T> r = new Result<>();
        r.setCode(1);
        r.setMsg(msg);
        return r;
    }

    public static <T> Result<T> fail(int code, String msg) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMsg(msg);
        return r;
    }
}
