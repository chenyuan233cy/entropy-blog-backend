package com.qichi.blog.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Defence in depth, not the primary XSS control.
 *
 * <p>The real controls are: every text field is rendered as text by React, and the only rich HTML
 * in the system (an article body) is cleaned through a jsoup allowlist before it is stored.
 *
 * <p>This filter removes angle brackets from query parameters, where they can never be legitimate,
 * so that a payload echoed back by mistake still cannot open a tag. Request bodies are left alone
 * because the article body is markdown and escaping it here would corrupt the content.
 */
public class XssFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if ("GET".equals(request.getMethod()) || "DELETE".equals(request.getMethod())) {
            chain.doFilter(new CleanQueryRequest(request), response);
        } else {
            chain.doFilter(request, response);
        }
    }

    static String clean(String value) {
        if (value == null) return null;
        return value.replace("<", "").replace(">", "");
    }

    private static final class CleanQueryRequest extends HttpServletRequestWrapper {

        CleanQueryRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getParameter(String name) {
            return clean(super.getParameter(name));
        }

        @Override
        public String[] getParameterValues(String name) {
            String[] values = super.getParameterValues(name);
            if (values == null) return null;
            String[] cleaned = new String[values.length];
            for (int i = 0; i < values.length; i++) cleaned[i] = clean(values[i]);
            return cleaned;
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            Map<String, String[]> out = new LinkedHashMap<>();
            super.getParameterMap().forEach((key, values) -> {
                String[] cleaned = new String[values.length];
                for (int i = 0; i < values.length; i++) cleaned[i] = clean(values[i]);
                out.put(key, cleaned);
            });
            return out;
        }
    }
}
