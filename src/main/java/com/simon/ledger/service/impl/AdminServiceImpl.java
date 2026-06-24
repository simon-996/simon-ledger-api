package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.dto.req.AdminLoginReq;
import com.simon.ledger.dto.resp.AdminAuditLogResp;
import com.simon.ledger.dto.resp.AdminDashboardResp;
import com.simon.ledger.dto.resp.AdminLedgerRecordResp;
import com.simon.ledger.dto.resp.AdminLoginResp;
import com.simon.ledger.dto.resp.AdminSystemHealthResp;
import com.simon.ledger.dto.resp.AdminUserRecordResp;
import com.simon.ledger.dto.resp.AdminUserResp;
import com.simon.ledger.dto.resp.PageResp;
import com.simon.ledger.entity.AdminOperationLog;
import com.simon.ledger.entity.AdminUser;
import com.simon.ledger.entity.Ledger;
import com.simon.ledger.entity.LedgerChangeLog;
import com.simon.ledger.entity.LedgerMember;
import com.simon.ledger.entity.LedgerPerson;
import com.simon.ledger.entity.LedgerTransaction;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.AdminOperationLogMapper;
import com.simon.ledger.mapper.AdminUserMapper;
import com.simon.ledger.mapper.LedgerChangeLogMapper;
import com.simon.ledger.mapper.LedgerMapper;
import com.simon.ledger.mapper.LedgerMemberMapper;
import com.simon.ledger.mapper.LedgerPersonMapper;
import com.simon.ledger.mapper.LedgerTransactionMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import com.simon.ledger.service.AdminService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl extends ServiceImpl<AdminUserMapper, AdminUser> implements AdminService {

    private static final String LOGIN_PREFIX = "admin:";
    private static final int STATUS_NORMAL = 1;
    private static final int STATUS_DISABLED = 2;

    private final AdminOperationLogMapper adminOperationLogMapper;
    private final UserAccountMapper userAccountMapper;
    private final LedgerMapper ledgerMapper;
    private final LedgerMemberMapper ledgerMemberMapper;
    private final LedgerPersonMapper ledgerPersonMapper;
    private final LedgerTransactionMapper ledgerTransactionMapper;
    private final LedgerChangeLogMapper ledgerChangeLogMapper;
    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;

    @Override
    public AdminLoginResp login(AdminLoginReq req) {
        AdminUser admin = getOne(Wrappers.<AdminUser>lambdaQuery()
                .eq(AdminUser::getAccount, req.getAccount().trim())
                .isNull(AdminUser::getDeletedAt));
        if (admin == null || !StringUtils.hasText(admin.getPasswordHash())
                || !BCrypt.checkpw(req.getPassword(), admin.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "账号或密码错误");
        }
        if (STATUS_DISABLED == admin.getStatus()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "后台账号已禁用");
        }

        StpUtil.login(LOGIN_PREFIX + admin.getId());
        admin.setLastLoginAt(LocalDateTime.now());
        updateById(admin);
        recordOperation(admin.getId(), "login", "admin_user", admin.getUuid(), "后台登录");

        AdminLoginResp resp = new AdminLoginResp();
        resp.setTokenName(StpUtil.getTokenName());
        resp.setTokenValue(StpUtil.getTokenValue());
        resp.setUser(toAdminUserResp(admin));
        return resp;
    }

    @Override
    public void logout() {
        AdminUser admin = currentAdmin();
        recordOperation(admin.getId(), "logout", "admin_user", admin.getUuid(), "后台退出");
        StpUtil.logout();
    }

    @Override
    public AdminUserResp me() {
        return toAdminUserResp(currentAdmin());
    }

    @Override
    public AdminDashboardResp dashboard() {
        currentAdmin();
        AdminDashboardResp resp = new AdminDashboardResp();
        resp.setUserCount(userAccountMapper.selectCount(Wrappers.<UserAccount>lambdaQuery().isNull(UserAccount::getDeletedAt)));
        resp.setLedgerCount(ledgerMapper.selectCount(Wrappers.<Ledger>lambdaQuery().isNull(Ledger::getDeletedAt)));
        resp.setDisabledUserCount(userAccountMapper.selectCount(Wrappers.<UserAccount>lambdaQuery()
                .eq(UserAccount::getStatus, STATUS_DISABLED)
                .isNull(UserAccount::getDeletedAt)));
        resp.setRecentChangeCount(ledgerChangeLogMapper.selectCount(Wrappers.<LedgerChangeLog>lambdaQuery()
                .ge(LedgerChangeLog::getCreatedAt, LocalDateTime.now().minusDays(7))));
        return resp;
    }

    @Override
    public PageResp<AdminUserRecordResp> users(String keyword, Integer page, Integer pageSize) {
        currentAdmin();
        LambdaQueryWrapper<UserAccount> wrapper = Wrappers.<UserAccount>lambdaQuery()
                .isNull(UserAccount::getDeletedAt)
                .orderByDesc(UserAccount::getCreatedAt);
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            wrapper.and(q -> q.like(UserAccount::getUuid, value)
                    .or()
                    .like(UserAccount::getNickname, value)
                    .or()
                    .like(UserAccount::getEmail, value)
                    .or()
                    .like(UserAccount::getPhone, value));
        }

        IPage<UserAccount> result = userAccountMapper.selectPage(new Page<>(page(page), pageSize(pageSize)), wrapper);
        List<AdminUserRecordResp> records = result.getRecords().stream().map(this::toUserRecordResp).toList();
        return pageResp(result, records);
    }

    @Override
    public PageResp<AdminLedgerRecordResp> ledgers(String keyword, Integer page, Integer pageSize) {
        currentAdmin();
        LambdaQueryWrapper<Ledger> wrapper = Wrappers.<Ledger>lambdaQuery()
                .isNull(Ledger::getDeletedAt)
                .orderByDesc(Ledger::getUpdatedAt);
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            wrapper.and(q -> q.like(Ledger::getUuid, value)
                    .or()
                    .like(Ledger::getName, value));
        }

        IPage<Ledger> result = ledgerMapper.selectPage(new Page<>(page(page), pageSize(pageSize)), wrapper);
        Map<Long, UserAccount> ownerMap = ownerMap(result.getRecords());
        List<AdminLedgerRecordResp> records = result.getRecords().stream()
                .map(ledger -> toLedgerRecordResp(ledger, ownerMap.get(ledger.getOwnerUserId())))
                .toList();
        return pageResp(result, records);
    }

    @Override
    public PageResp<AdminAuditLogResp> auditLogs(Integer page, Integer pageSize) {
        currentAdmin();
        IPage<AdminOperationLog> result = adminOperationLogMapper.selectPage(
                new Page<>(page(page), pageSize(pageSize)),
                Wrappers.<AdminOperationLog>lambdaQuery().orderByDesc(AdminOperationLog::getCreatedAt));
        Map<Long, AdminUser> adminMap = adminMap(result.getRecords());
        List<AdminAuditLogResp> records = result.getRecords().stream()
                .map(log -> toAuditLogResp(log, adminMap.get(log.getAdminUserId())))
                .toList();
        return pageResp(result, records);
    }

    @Override
    public AdminSystemHealthResp systemHealth() {
        currentAdmin();
        AdminSystemHealthResp resp = new AdminSystemHealthResp();
        List<AdminSystemHealthResp.Item> items = new ArrayList<>();
        items.add(apiHealth());
        items.add(databaseHealth());
        items.add(redisHealth());
        resp.setItems(items);
        return resp;
    }

    private AdminUser currentAdmin() {
        Object loginId = StpUtil.getLoginId();
        String value = loginId == null ? "" : loginId.toString();
        if (!value.startsWith(LOGIN_PREFIX)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不是后台登录态");
        }
        Long adminId;
        try {
            adminId = Long.valueOf(value.substring(LOGIN_PREFIX.length()));
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "后台登录态无效");
        }
        AdminUser admin = getById(adminId);
        if (admin == null || admin.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "后台账号不存在");
        }
        if (STATUS_DISABLED == admin.getStatus()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "后台账号已禁用");
        }
        return admin;
    }

    private AdminUserResp toAdminUserResp(AdminUser admin) {
        AdminUserResp resp = new AdminUserResp();
        resp.setUuid(admin.getUuid());
        resp.setAccount(admin.getAccount());
        resp.setNickname(admin.getNickname());
        resp.setRole(admin.getRole());
        resp.setStatus(admin.getStatus());
        return resp;
    }

    private AdminUserRecordResp toUserRecordResp(UserAccount user) {
        AdminUserRecordResp resp = new AdminUserRecordResp();
        resp.setUuid(user.getUuid());
        resp.setNickname(user.getNickname());
        resp.setAccount(account(user));
        resp.setStatus(user.getStatus());
        resp.setLedgerCount(ledgerMapper.selectCount(Wrappers.<Ledger>lambdaQuery()
                .eq(Ledger::getOwnerUserId, user.getId())
                .isNull(Ledger::getDeletedAt)));
        resp.setJoinedCount(ledgerMemberMapper.selectCount(Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getUserId, user.getId())
                .isNull(LedgerMember::getDeletedAt)));
        resp.setCreatedAt(user.getCreatedAt());
        resp.setUpdatedAt(user.getUpdatedAt());
        return resp;
    }

    private AdminLedgerRecordResp toLedgerRecordResp(Ledger ledger, UserAccount owner) {
        AdminLedgerRecordResp resp = new AdminLedgerRecordResp();
        resp.setUuid(ledger.getUuid());
        resp.setName(ledger.getName());
        resp.setOwner(owner == null ? "未知用户" : owner.getNickname());
        resp.setOwnerUserUuid(owner == null ? null : owner.getUuid());
        resp.setMemberCount(countInt(ledgerMemberMapper.selectCount(Wrappers.<LedgerMember>lambdaQuery()
                .eq(LedgerMember::getLedgerId, ledger.getId())
                .isNull(LedgerMember::getDeletedAt))));
        resp.setPersonCount(countInt(ledgerPersonMapper.selectCount(Wrappers.<LedgerPerson>lambdaQuery()
                .eq(LedgerPerson::getLedgerId, ledger.getId())
                .isNull(LedgerPerson::getDeletedAt))));
        resp.setTransactionCount(countInt(ledgerTransactionMapper.selectCount(Wrappers.<LedgerTransaction>lambdaQuery()
                .eq(LedgerTransaction::getLedgerId, ledger.getId())
                .isNull(LedgerTransaction::getDeletedAt))));
        resp.setStatus("synced");
        resp.setUpdatedAt(ledger.getUpdatedAt());
        return resp;
    }

    private AdminAuditLogResp toAuditLogResp(AdminOperationLog log, AdminUser admin) {
        AdminAuditLogResp resp = new AdminAuditLogResp();
        resp.setUuid(log.getUuid());
        resp.setActor(admin == null ? "system" : admin.getAccount());
        resp.setAction(log.getAction());
        resp.setTarget(StringUtils.hasText(log.getDetail()) ? log.getDetail() : log.getTargetType());
        resp.setLevel("info");
        resp.setCreatedAt(log.getCreatedAt());
        return resp;
    }

    private Map<Long, UserAccount> ownerMap(List<Ledger> ledgers) {
        Set<Long> ownerIds = ledgers.stream().map(Ledger::getOwnerUserId).collect(Collectors.toSet());
        if (ownerIds.isEmpty()) {
            return Map.of();
        }
        return userAccountMapper.selectList(Wrappers.<UserAccount>lambdaQuery().in(UserAccount::getId, ownerIds))
                .stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
    }

    private Map<Long, AdminUser> adminMap(List<AdminOperationLog> logs) {
        Set<Long> adminIds = logs.stream().map(AdminOperationLog::getAdminUserId).collect(Collectors.toSet());
        if (adminIds.isEmpty()) {
            return Map.of();
        }
        return listByIds(adminIds).stream().collect(Collectors.toMap(AdminUser::getId, Function.identity()));
    }

    private void recordOperation(Long adminUserId, String action, String targetType, String targetUuid, String detail) {
        AdminOperationLog log = new AdminOperationLog();
        log.setUuid(IdUtil.fastSimpleUUID());
        log.setAdminUserId(adminUserId);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetUuid(targetUuid);
        log.setDetail(detail);
        log.setIpAddress(ipAddress());
        log.setCreatedAt(LocalDateTime.now());
        adminOperationLogMapper.insert(log);
    }

    private String account(UserAccount user) {
        if (StringUtils.hasText(user.getEmail())) {
            return user.getEmail();
        }
        return user.getPhone();
    }

    private String ipAddress() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private AdminSystemHealthResp.Item apiHealth() {
        AdminSystemHealthResp.Item item = new AdminSystemHealthResp.Item();
        item.setName("API 服务");
        item.setStatus("healthy");
        item.setLatency("0 ms");
        item.setDetail("Spring Boot 已响应");
        return item;
    }

    private AdminSystemHealthResp.Item databaseHealth() {
        long start = System.nanoTime();
        AdminSystemHealthResp.Item item = new AdminSystemHealthResp.Item();
        item.setName("MySQL");
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            item.setStatus(valid ? "healthy" : "down");
            item.setDetail(valid ? "连接正常" : "连接不可用");
        } catch (Exception e) {
            item.setStatus("down");
            item.setDetail(e.getMessage());
        }
        item.setLatency(latency(start));
        return item;
    }

    private AdminSystemHealthResp.Item redisHealth() {
        long start = System.nanoTime();
        AdminSystemHealthResp.Item item = new AdminSystemHealthResp.Item();
        item.setName("Redis");
        try {
            redisTemplate.opsForValue().get("admin:health:probe");
            item.setStatus("healthy");
            item.setDetail("连接正常");
        } catch (Exception e) {
            item.setStatus("down");
            item.setDetail(e.getMessage());
        }
        item.setLatency(latency(start));
        return item;
    }

    private String latency(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis() + " ms";
    }

    private int countInt(Long value) {
        if (value == null) {
            return 0;
        }
        return Math.toIntExact(value);
    }

    private long page(Integer page) {
        if (page == null || page < 1) {
            return 1;
        }
        return page;
    }

    private long pageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }

    private <T, R> PageResp<R> pageResp(IPage<T> page, List<R> records) {
        PageResp<R> resp = new PageResp<>();
        resp.setPage(page.getCurrent());
        resp.setPageSize(page.getSize());
        resp.setTotal(page.getTotal());
        resp.setRecords(records);
        return resp;
    }
}
