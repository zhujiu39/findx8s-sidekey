// SPDX-License-Identifier: GPL-3.0-or-later
package cn.sidekey.mijia;

import org.json.JSONObject;

final class Failure extends Exception {
    final String code;
    Failure(String code, String message) { super(message); this.code = code; }
    static JSONObject json(Throwable error) {
        try {
            // 不向日志、WebUI 或系统进程输出原始 HTTP 响应、URL、令牌或异常堆栈。
            if (error instanceof Failure) return Json.obj("ok", false, "code", ((Failure) error).code, "message", error.getMessage());
            if (error instanceof java.io.IOException) return Json.obj("ok", false, "code", "NETWORK", "message", "网络请求失败，请检查连接后重试");
            if (error instanceof org.json.JSONException) return Json.obj("ok", false, "code", "PROTOCOL", "message", "米家响应格式与当前版本不兼容");
            return Json.obj("ok", false, "code", "INTERNAL", "message", "米家服务处理失败（" + error.getClass().getSimpleName() + "）");
        } catch (Exception impossible) { return new JSONObject(); }
    }
}
