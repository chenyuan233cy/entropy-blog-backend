package com.qichi.blog.modules.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存活探针。给 systemd / Nginx / uptime 监控用，不查数据库也不查 Redis：
 * 那两样挂了站点还能降级运行，探针跟着红只会带来误告警。
 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("time", Instant.now().toString());
        return body;
    }
}
