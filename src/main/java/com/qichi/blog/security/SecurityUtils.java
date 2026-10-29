package com.qichi.blog.security;

import com.qichi.blog.common.BizException;
import com.qichi.blog.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static Optional<LoginUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return Optional.empty();
        Object principal = authentication.getPrincipal();
        return principal instanceof LoginUser user ? Optional.of(user) : Optional.empty();
    }

    public static LoginUser requireUser() {
        return currentUser().orElseThrow(() -> new BizException(ErrorCode.UNAUTHENTICATED));
    }
}
