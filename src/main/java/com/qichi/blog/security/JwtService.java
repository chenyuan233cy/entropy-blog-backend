package com.qichi.blog.security;

import com.qichi.blog.common.BizException;
import com.qichi.blog.common.ErrorCode;
import com.qichi.blog.config.BlogProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final BlogProperties.Jwt config;

    public JwtService(BlogProperties properties) {
        this.config = properties.jwt();
        byte[] secret = config.secret() == null ? new byte[0] : config.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("blog.jwt.secret must be at least 32 characters long");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public String createAccessToken(LoginUser user, long tokenVersion) {
        return create(user, TYPE_ACCESS, config.accessTtl(), tokenVersion);
    }

    public String createRefreshToken(LoginUser user, long tokenVersion) {
        return create(user, TYPE_REFRESH, config.refreshTtl(), tokenVersion);
    }

    public String create(LoginUser user, String type, Duration ttl, long tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(Long.toString(user.id()))
                .id(UUID.randomUUID().toString().replace("-", ""))
                .issuer(config.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .claim("typ", type)
                .claim("ver", tokenVersion)
                .claim("usr", user.username())
                .claim("nick", user.nickname())
                .claim("role", user.role())
                .signWith(key)
                .compact();
    }

    /** Verifies signature, issuer and expiry. Maps jjwt failures onto our own error codes. */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(config.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            throw new BizException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
    }

    public LoginUser toLoginUser(Claims claims) {
        return new LoginUser(
                Long.parseLong(claims.getSubject()),
                claims.get("usr", String.class),
                claims.get("nick", String.class),
                claims.get("role", String.class));
    }

    public long tokenVersion(Claims claims) {
        Object value = claims.get("ver");
        if (value instanceof Number number) return number.longValue();
        return 0L;
    }

    public Duration accessTtl() { return config.accessTtl(); }
    public Duration refreshTtl() { return config.refreshTtl(); }
    public Duration refreshGrace() { return config.refreshGrace(); }
}
