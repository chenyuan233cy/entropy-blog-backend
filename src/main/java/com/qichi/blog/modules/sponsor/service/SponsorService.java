package com.qichi.blog.modules.sponsor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qichi.blog.common.BizException;
import com.qichi.blog.modules.sponsor.SponsorDtos;
import com.qichi.blog.modules.sponsor.entity.Sponsor;
import com.qichi.blog.modules.sponsor.mapper.SponsorMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * 赞助名单。前台只读：谁赞助过、说了什么。
 * 排序按「站长手动排的权重 → 金额 → 日期」，权重优先是为了能把特别想感谢的人固定在上面。
 */
@Service
public class SponsorService {

    private final SponsorMapper mapper;

    public SponsorService(SponsorMapper mapper) {
        this.mapper = mapper;
    }

    public List<SponsorDtos.SponsorVO> listVisible() {
        return mapper.selectList(Wrappers.<Sponsor>lambdaQuery().eq(Sponsor::getVisible, 1))
                .stream()
                .sorted(Comparator
                        .comparingInt((Sponsor s) -> s.getSortOrder() == null ? 0 : s.getSortOrder()).reversed()
                        .thenComparing((Sponsor s) -> s.getAmount() == null ? BigDecimal.ZERO : s.getAmount(), Comparator.reverseOrder())
                        .thenComparing((Sponsor s) -> s.getSponsoredAt() == null ? LocalDate.MIN : s.getSponsoredAt(), Comparator.reverseOrder()))
                .map(SponsorService::toVO)
                .toList();
    }

    /** 后台列表：连隐藏的一起给，站长要能编辑。 */
    public List<SponsorDtos.SponsorVO> listAll() {
        return mapper.selectList(Wrappers.<Sponsor>lambdaQuery().orderByDesc(Sponsor::getSortOrder)
                        .orderByDesc(Sponsor::getCreateTime))
                .stream().map(SponsorService::toVO).toList();
    }

    @Transactional
    public SponsorDtos.SponsorVO create(SponsorDtos.SponsorRequest request) {
        Sponsor sponsor = new Sponsor();
        apply(sponsor, request);
        mapper.insert(sponsor);
        return toVO(sponsor);
    }

    @Transactional
    public SponsorDtos.SponsorVO update(Long id, SponsorDtos.SponsorRequest request) {
        Sponsor sponsor = mapper.selectById(id);
        if (sponsor == null) throw BizException.notFound("赞助记录");
        apply(sponsor, request);
        mapper.updateById(sponsor);
        return toVO(sponsor);
    }

    @Transactional
    public void delete(Long id) {
        if (mapper.selectById(id) == null) throw BizException.notFound("赞助记录");
        mapper.deleteById(id);
    }

    private void apply(Sponsor sponsor, SponsorDtos.SponsorRequest request) {
        sponsor.setName(request.name().trim());
        sponsor.setAmount(request.amount() == null ? BigDecimal.ZERO : request.amount());
        sponsor.setMessage(blankToNull(request.message()));
        sponsor.setAvatarUrl(blankToNull(request.avatarUrl()));
        sponsor.setLink(blankToNull(request.link()));
        sponsor.setSponsoredAt(request.sponsoredAt() == null ? LocalDate.now() : request.sponsoredAt());
        sponsor.setVisible(request.visible() == null || request.visible() ? 1 : 0);
        sponsor.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static SponsorDtos.SponsorVO toVO(Sponsor sponsor) {
        return new SponsorDtos.SponsorVO(
                sponsor.getId(), sponsor.getName(),
                sponsor.getAmount() == null ? BigDecimal.ZERO : sponsor.getAmount(),
                sponsor.getMessage(), sponsor.getAvatarUrl(), sponsor.getLink(), sponsor.getSponsoredAt(),
                sponsor.getVisible() != null && sponsor.getVisible() == 1,
                sponsor.getSortOrder() == null ? 0 : sponsor.getSortOrder());
    }
}
