package com.qichi.blog.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException ex, HttpServletRequest request) {
        ErrorCode code = ex.errorCode();
        if (code.httpStatus() >= 500) {
            log.error("{} {} -> {}", request.getMethod(), request.getRequestURI(), ex.getMessage(), ex);
        } else {
            log.debug("{} {} -> {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        }
        return ResponseEntity.status(code.httpStatus()).body(R.fail(code, ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Map<String, String>>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = fieldErrors(ex.getBindingResult().getFieldErrors());
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.httpStatus())
                .body(R.fail(ErrorCode.PARAM_INVALID, firstFieldMessage(fields), fields));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Map<String, String>>> handleBind(BindException ex) {
        Map<String, String> fields = fieldErrors(ex.getFieldErrors());
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.httpStatus())
                .body(R.fail(ErrorCode.PARAM_INVALID, firstFieldMessage(fields), fields));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<R<Void>> handleBadParam(Exception ex) {
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.httpStatus())
                .body(R.fail(ErrorCode.PARAM_INVALID, ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.httpStatus())
                .body(R.fail(ErrorCode.BAD_REQUEST, "请求体格式错误"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<R<Void>> handleAuth(AuthenticationException ex) {
        return ResponseEntity.status(ErrorCode.UNAUTHENTICATED.httpStatus())
                .body(R.fail(ErrorCode.UNAUTHENTICATED, ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleDenied(AccessDeniedException ex) {
        return ResponseEntity.status(ErrorCode.FORBIDDEN.httpStatus())
                .body(R.fail(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.httpStatus())
                .body(R.fail(ErrorCode.NOT_FOUND, "接口不存在"));
    }

    /** 上传超限：说清楚是文件太大，别丢一句「服务器内部错误」让人猜。 */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> handleUploadTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        log.warn("upload rejected: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.httpStatus())
                .body(R.fail(ErrorCode.PARAM_INVALID, "文件太大，超过了服务器允许的上传上限"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleOther(Exception ex, HttpServletRequest request) {
        log.error("unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(ErrorCode.INTERNAL.httpStatus())
                .body(R.fail(ErrorCode.INTERNAL, ErrorCode.INTERNAL.defaultMessage()));
    }

    private Map<String, String> fieldErrors(java.util.List<FieldError> errors) {
        Map<String, String> out = new LinkedHashMap<>();
        for (FieldError error : errors) {
            out.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return out;
    }

    /**
     * 校验失败时把第一条具体原因放进 message。否则前端只能拿到「参数校验失败」，
     * 搞不清是标题没填还是内容太短 —— 具体原因本来就在 data 里，只是没人看。
     */
    private String firstFieldMessage(Map<String, String> fields) {
        return fields.values().stream()
                .filter(message -> message != null && !message.isBlank())
                .findFirst()
                .orElse(ErrorCode.PARAM_INVALID.defaultMessage());
    }
}
