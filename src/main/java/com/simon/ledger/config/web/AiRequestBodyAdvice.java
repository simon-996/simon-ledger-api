package com.simon.ledger.config.web;

import com.simon.ledger.dto.req.AiParseReq;
import com.simon.ledger.service.impl.AiBookkeepingAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import java.lang.reflect.Type;
import java.io.IOException;

@ControllerAdvice
@RequiredArgsConstructor
public class AiRequestBodyAdvice extends RequestBodyAdviceAdapter {
    private final AiBookkeepingAccess access;
    @Override public boolean supports(MethodParameter parameter, Type target, Class<? extends HttpMessageConverter<?>> converter) {
        return target == AiParseReq.class;
    }
    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter, Type target,
            Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        var attributes = (org.springframework.web.context.request.ServletRequestAttributes)
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes();
        var variables = (java.util.Map<?, ?>) attributes.getRequest().getAttribute(
                org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String ledgerUuid = variables == null ? null : (String) variables.get("ledgerUuid");
        access.requireAllowed(ledgerUuid);
        final int maxBytes = 32768;
        if (message.getHeaders().getContentLength() > maxBytes) throw oversized();
        byte[] body = message.getBody().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) throw oversized();
        return new HttpInputMessage() {
            @Override public java.io.InputStream getBody() { return new java.io.ByteArrayInputStream(body); }
            @Override public org.springframework.http.HttpHeaders getHeaders() { return message.getHeaders(); }
        };
    }
    private com.simon.ledger.common.exception.BusinessException oversized() {
        return new com.simon.ledger.common.exception.BusinessException(com.simon.ledger.common.ErrorCode.BAD_REQUEST, "AI 文字请求不能超过 32 KiB");
    }
}
