package com.labex.common;

import com.labex.auth.AuthErrorCode;
import com.labex.auth.AuthException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常兜底：任何未被 Controller 捕获的异常都转成统一 Result，
 * 保证前端始终能拿到带 message 的响应体（避免 Spring 默认错误 JSON 缺 message）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthException.class)
    public Result<Void> handleAuthException(AuthException e, HttpServletResponse response) {
        if (e.getCode() == AuthErrorCode.RATE_LIMITED) {
            response.setStatus(429);
        } else if (e.getCode() == AuthErrorCode.REDIS_UNAVAILABLE) {
            response.setStatus(503);
        }
        if (e.getRetryAfterSeconds() > 0) {
            response.setHeader("Retry-After", String.valueOf(e.getRetryAfterSeconds()));
        }
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e, HttpServletResponse response) {
        response.setStatus(400);
        log.warn("Request body unreadable: {}", e.getMessage());
        return Result.error(-1007, "请求体格式错误，请检查 JSON 格式后重试");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e, HttpServletResponse response) {
        response.setStatus(400);
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null
                ? "请求参数校验失败"
                : "参数错误：" + fieldError.getField() + " " + fieldError.getDefaultMessage();
        return Result.error(-1007, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraint(ConstraintViolationException e, HttpServletResponse response) {
        response.setStatus(400);
        return Result.error(-1007, "参数校验失败：" + e.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e, HttpServletResponse response) {
        response.setStatus(400);
        return Result.error(-1007, "缺少必要参数：" + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletResponse response) {
        response.setStatus(400);
        return Result.error(-1007, "参数类型错误：" + e.getName());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e, HttpServletResponse response) {
        response.setStatus(405);
        return Result.error(-1, "请求方法不支持：" + e.getMethod());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNotFound(NoResourceFoundException e, HttpServletResponse response) {
        response.setStatus(404);
        return Result.error(-1, "接口不存在：" + e.getResourcePath());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Result<Void> handleResponseStatus(ResponseStatusException e, HttpServletResponse response) {
        int status = e.getStatusCode().value();
        response.setStatus(status);
        if (status >= 500) {
            log.error("Unhandled exception in request handling", e);
            return Result.error(-1, "服务器内部错误，请稍后重试（" + e.getClass().getSimpleName() + "）");
        }
        String message = e.getReason() == null ? e.getMessage() : e.getReason();
        return Result.error(-1, message);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e, HttpServletResponse response) {
        response.setStatus(500);
        log.error("Unhandled exception in request handling", e);
        return Result.error(-1, "服务器内部错误，请稍后重试（" + e.getClass().getSimpleName() + "）");
    }
}