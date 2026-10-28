package ai.momobird.halo.core;

/**
 * A failed call to the MomoBird connect API. The message is a Chinese sentence for the blogger and
 * has already been through {@link Redactor}; the error keeps no cause, so no lower-level exception
 * (which might echo the request) can reach a log or a response through it.
 */
public final class ConnectError extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final int MAX_REASON = 200;

    public enum Kind {
        /** No usable answer: refused, reset, timed out, cut off, body too large. Retried. */
        NETWORK,
        /** Server errors (5xx, retried) and answers that cannot be understood (3xx, a 2xx without data). */
        HTTP,
        /** 402: the plan has run out. */
        PLAN_REQUIRED,
        /** 401: the key is wrong or revoked. */
        UNAUTHORIZED,
        /** 409 SOURCE_DISABLED: the knowledge source was switched off in the console. */
        DISABLED,
        /** 413: the request body was too large. */
        TOO_LARGE,
        /** Any other 4xx: the server refused this request (VALIDATION_FAILED, WIDGET_NOT_READY ...). */
        REJECTED
    }

    private final Kind kind;
    private final Integer status;
    private final String code;

    private ConnectError(Kind kind, Integer status, String code, String message) {
        super(message, null, false, true);
        this.kind = kind;
        this.status = status;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    /** The HTTP status, or {@code null} for a network error. */
    public Integer status() {
        return status;
    }

    /** The server's short error code (for example {@code VALIDATION_FAILED}), or {@code null}. */
    public String code() {
        return code;
    }

    public boolean isWidgetNotReady() {
        return kind == Kind.REJECTED && "WIDGET_NOT_READY".equals(code);
    }

    /** A network failure; {@code reason} is cleaned of the key, flattened and cut to a sensible size. */
    public static ConnectError network(String reason, String key) {
        String text = Redactor.clean(reason, key).replaceAll("\\s+", " ").strip();
        if (text.length() > MAX_REASON) {
            text = text.substring(0, MAX_REASON) + "…";
        }
        if (text.isEmpty()) {
            text = "未知错误";
        }
        return new ConnectError(Kind.NETWORK, null, null, "连不上 MomoBird：" + text);
    }

    public static ConnectError network(Throwable cause, String key) {
        String text = cause == null ? "" : cause.getMessage();
        if (text == null || text.isBlank()) {
            text = cause == null ? "" : cause.getClass().getSimpleName();
        }
        return network(text, key);
    }

    /** The key cannot go into an HTTP header. Not retried; the message never repeats the key. */
    public static ConnectError unsendableKey() {
        return new ConnectError(Kind.UNAUTHORIZED, null, null, "连接密钥包含无法发送的字符");
    }

    /** An error answer from the server. {@code code} must already be known to be code-shaped and free of the key. */
    public static ConnectError http(int status, String code) {
        Kind kind;
        String message;
        if (status == 401) {
            kind = Kind.UNAUTHORIZED;
            message = "连接密钥无效或已被吊销，请在 MomoBird 控制台重新生成";
        } else if (status == 402) {
            kind = Kind.PLAN_REQUIRED;
            message = "套餐已到期，同步需要专业版或试用";
        } else if (status == 409 && "SOURCE_DISABLED".equals(code)) {
            kind = Kind.DISABLED;
            message = "这个知识源已在 MomoBird 控制台停用";
        } else if (status == 409 && "WIDGET_NOT_READY".equals(code)) {
            kind = Kind.REJECTED;
            message = "文章还在建索引，问答助手下次同步时再开启";
        } else if (status == 413) {
            kind = Kind.TOO_LARGE;
            message = "一次提交的内容太大，被 MomoBird 拒绝（HTTP 413）";
        } else if (status >= 500) {
            kind = Kind.HTTP;
            message = "MomoBird 暂时不可用（HTTP " + status + "）";
        } else if (status >= 400) {
            kind = Kind.REJECTED;
            message = "MomoBird 拒绝了这次请求（HTTP " + status + (code == null ? "" : " " + code) + "）";
        } else {
            kind = Kind.HTTP;
            message = "MomoBird 返回错误（HTTP " + status + (code == null ? "" : " " + code) + "）";
        }
        return new ConnectError(kind, status, code, message);
    }
}
