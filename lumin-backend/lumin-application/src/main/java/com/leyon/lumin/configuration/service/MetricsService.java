package com.leyon.lumin.configuration.service;

import com.leyon.lumin.configuration.repository.MetricsMapper;
import com.leyon.lumin.configuration.repository.MetricsMapper.BandReads;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可信产品与供给看板（docs/13 UGC-040 一阶段）：指标口径**冻结版本化**，
 * 任何读数都带 frozenDefinitionVersion；**无数据不编数字**——空窗口返回
 * 空集/空数组而非造零；**合成流量剔除**（演示替身与骨架档案）且剔除量
 * 透明呈现；事件去重由 COUNT DISTINCT + 唯一约束天然保证。
 *
 * <p>冻结口径 v1（本版本起任何调整必须升版本并留痕）：
 * <ul>
 *   <li>有效阅读 = 窗口内 reading_progress 更新的去重 (subject, chapter) 行，
 *       剔除合成流量（oidc_subject 以 `sub-` 开头的替身/骨架档案）与注销行；</li>
 *   <li>次周留存 = 按读者首次触点所在自然周（周一起算）分群，
 *       次周仍有触点记为留存；分群人数为 0 不产出（不造 0%）；</li>
 *   <li>供给健康 = 发布流程提交/通过/驳回、生效下架、生效精选在册数。</li>
 * </ul>
 * 分端（Web/Android）随客户端平台标识埋点批次；本阶段分群维度为年龄带。
 */
@Service
public class MetricsService {

    /** 冻结口径版本（口径调整必须升版本）。 */
    public static final int FROZEN_DEFINITION_VERSION = 1;

    /** 合成流量剔除前缀（替身/骨架档案的 oidc_subject 约定）。 */
    public static final String SYNTHETIC_SUBJECT_PREFIX = "sub-";

    private static final Set<String> METRIC_READERS = Set.of("OPERATOR", "ADMIN");
    private static final int MAX_WINDOW_DAYS = 90;

    private final MetricsMapper metricsMapper;

    public MetricsService(MetricsMapper metricsMapper) {
        this.metricsMapper = metricsMapper;
    }

    /** 有效阅读看板（OPERATOR/ADMIN；窗口 1–90 天，越界 400）。 */
    @Transactional(readOnly = true)
    public ReadingView reading(String stubRole, Integer days) {
        requireRole(stubRole);
        int windowDays = days == null ? 7 : days;
        if (windowDays < 1 || windowDays > MAX_WINDOW_DAYS) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        LocalDateTime windowStart = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.DAYS)
                .minusDays(windowDays);
        List<BandReads> bands = metricsMapper.readsByBand(windowStart);
        List<Map<String, Object>> byBand = bands.stream()
                .map(band -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("band", band.band());
                    view.put("reads", band.readCount());
                    return view;
                })
                .toList();
        return new ReadingView(FROZEN_DEFINITION_VERSION, windowDays,
                windowStart.toInstant(ZoneOffset.UTC).toString(),
                metricsMapper.countEffectiveReads(windowStart),
                metricsMapper.countSyntheticReads(windowStart), byBand);
    }

    /**
     * 次周留存（OPERATOR/ADMIN）：按首次触点自然周分群（周一起算）；
     * 分群人数 0 不产出——无数据不编数字。
     */
    @Transactional(readOnly = true)
    public RetentionView retention(String stubRole) {
        requireRole(stubRole);
        LocalDateTime windowStart = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.DAYS).minusDays(28);
        // 触点按时间升序——首读周取最早触点（DISTINCT 无序，必须显式排序）
        List<MetricsMapper.ProgressTouch> touches = metricsMapper.progressTouches(windowStart)
                .stream()
                .sorted(java.util.Comparator.comparing(MetricsMapper.ProgressTouch::updatedAt))
                .toList();
        // 触点周归属 + 读者首读周（周一起算，UTC）
        Map<LocalDate, Set<String>> activeWeeks = new LinkedHashMap<>();
        Map<String, LocalDate> firstWeek = new LinkedHashMap<>();
        for (MetricsMapper.ProgressTouch touch : touches) {
            LocalDate week = weekStart(touch.updatedAt());
            activeWeeks.computeIfAbsent(week, ignored -> new java.util.LinkedHashSet<>())
                    .add(touch.userId());
            firstWeek.putIfAbsent(touch.userId(), week);
        }
        Map<LocalDate, Set<String>> cohortMembers = new LinkedHashMap<>();
        for (Map.Entry<String, LocalDate> entry : firstWeek.entrySet()) {
            cohortMembers.computeIfAbsent(entry.getValue(), ignored -> new java.util.LinkedHashSet<>())
                    .add(entry.getKey());
        }
        List<Map<String, Object>> cohorts = new ArrayList<>();
        List<LocalDate> weeks = cohortMembers.keySet().stream().sorted().toList();
        for (LocalDate week : weeks) {
            Set<String> members = cohortMembers.get(week);
            if (members.isEmpty()) {
                continue; // 无数据不编数字：空分群不产出
            }
            Set<String> nextWeek = activeWeeks.getOrDefault(week.plusDays(7), Set.of());
            long retained = members.stream().filter(nextWeek::contains).count();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("weekStart", week.toString());
            view.put("cohortSize", members.size());
            view.put("retainedCount", retained);
            view.put("retentionRate", Math.round((double) retained / members.size() * 10000)
                    / 10000.0);
            cohorts.add(view);
        }
        return new RetentionView(FROZEN_DEFINITION_VERSION, cohorts);
    }

    /** 供给健康（OPERATOR/ADMIN）：提交/通过/驳回/下架/精选在册数。 */
    @Transactional(readOnly = true)
    public SupplyView supply(String stubRole) {
        requireRole(stubRole);
        return new SupplyView(FROZEN_DEFINITION_VERSION,
                metricsMapper.countSubmissions(), metricsMapper.countApproved(),
                metricsMapper.countRejected(), metricsMapper.countTakedownActive(),
                metricsMapper.countFeaturedActive(), metricsMapper.countRealReaders(),
                metricsMapper.countSyntheticProfiles());
    }

    /** 自然周起点（周一起算，UTC）。 */
    private static LocalDate weekStart(LocalDateTime moment) {
        LocalDate date = moment.toInstant(ZoneOffset.UTC)
                .atZone(ZoneOffset.UTC).toLocalDate();
        return date.with(java.time.temporal.TemporalAdjusters
                .previousOrSame(DayOfWeek.MONDAY));
    }

    private static void requireRole(String stubRole) {
        if (stubRole == null || !METRIC_READERS.contains(stubRole)) {
            throw new LuminBusinessException(ErrorCode.A2001);
        }
    }

    /** 有效阅读看板视图（口径版本随读数走）。 */
    public record ReadingView(int frozenDefinitionVersion, int windowDays,
                              String windowStart, long effectiveReads,
                              long syntheticExcluded, List<Map<String, Object>> byBand) {
    }

    /** 次周留存视图（空分群不产出）。 */
    public record RetentionView(int frozenDefinitionVersion,
                                List<Map<String, Object>> cohorts) {
    }

    /** 供给健康视图。 */
    public record SupplyView(int frozenDefinitionVersion, long submissions,
                             long approved, long rejected, long takedownActive,
                             long featuredActive, long realReaders,
                             long syntheticProfiles) {
    }
}
