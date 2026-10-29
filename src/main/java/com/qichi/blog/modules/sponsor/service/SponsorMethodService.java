package com.qichi.blog.modules.sponsor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qichi.blog.common.BizException;
import com.qichi.blog.modules.sponsor.SponsorDtos;
import com.qichi.blog.modules.sponsor.entity.SponsorMethod;
import com.qichi.blog.modules.sponsor.mapper.SponsorMethodMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 赞助方式：爱发电、赞赏码、银行卡号……每条是「名称 + 类型 + 内容」。
 *
 * <p>类型只有三种，各有各的渲染方式：
 * TEXT 直接显示文字，LINK 变成可点的链接，IMAGE 显示成图片（赞赏码那种）。
 */
@Service
public class SponsorMethodService {

    private final SponsorMethodMapper mapper;

    public SponsorMethodService(SponsorMethodMapper mapper) {
        this.mapper = mapper;
    }

    public List<SponsorDtos.SponsorMethodVO> list() {
        return mapper.selectList(Wrappers.<SponsorMethod>lambdaQuery()
                        .orderByAsc(SponsorMethod::getSortOrder)
                        .orderByAsc(SponsorMethod::getId))
                .stream().map(SponsorMethodService::toVO).toList();
    }

    @Transactional
    public SponsorDtos.SponsorMethodVO create(SponsorDtos.SponsorMethodRequest request) {
        SponsorMethod method = new SponsorMethod();
        apply(method, request);
        method.setCreateTime(LocalDateTime.now());
        mapper.insert(method);
        return toVO(method);
    }

    @Transactional
    public SponsorDtos.SponsorMethodVO update(Long id, SponsorDtos.SponsorMethodRequest request) {
        SponsorMethod method = require(id);
        apply(method, request);
        mapper.updateById(method);
        return toVO(mapper.selectById(id));
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        mapper.deleteById(id);
    }

    private SponsorMethod require(Long id) {
        SponsorMethod method = mapper.selectById(id);
        if (method == null) throw BizException.notFound("赞助方式");
        return method;
    }

    private void apply(SponsorMethod method, SponsorDtos.SponsorMethodRequest request) {
        String kind = request.kind() == null ? "" : request.kind().trim().toUpperCase(Locale.ROOT);
        if (!SponsorMethod.TEXT.equals(kind) && !SponsorMethod.LINK.equals(kind) && !SponsorMethod.IMAGE.equals(kind)) {
            throw BizException.invalid("类型只能是 文本 / 链接 / 图片");
        }
        String content = request.content() == null ? "" : request.content().trim();
        if (content.isEmpty()) throw BizException.invalid("内容不能为空");

        // 链接必须是 http(s)：别让 javascript: 之类的地址混进前台渲染
        if (SponsorMethod.LINK.equals(kind) && !content.matches("(?i)^https?://.+")) {
            throw BizException.invalid("链接要以 http:// 或 https:// 开头");
        }

        method.setName(request.name().trim());
        method.setKind(kind);
        method.setContent(content);
        method.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        method.setUpdateTime(LocalDateTime.now());
    }

    private static SponsorDtos.SponsorMethodVO toVO(SponsorMethod method) {
        return new SponsorDtos.SponsorMethodVO(
                method.getId(), method.getName(), method.getKind(), method.getContent(),
                method.getSortOrder() == null ? 0 : method.getSortOrder());
    }
}
