package com.simon.ledger.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.entity.AdminOperationLog;
import com.simon.ledger.entity.AdminUser;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.AdminOperationLogMapper;
import com.simon.ledger.mapper.AdminUserMapper;
import com.simon.ledger.mapper.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAiAccessServiceTests {
    @Mock private AdminUserMapper adminUserMapper;
    @Mock private UserAccountMapper userAccountMapper;
    @Mock private AdminOperationLogMapper operationLogMapper;
    private AdminServiceImpl service;

    @BeforeEach
    void setup() {
        if (com.baomidou.mybatisplus.core.metadata.TableInfoHelper.getTableInfo(UserAccount.class) == null)
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), UserAccount.class);

        service = new AdminServiceImpl(operationLogMapper, userAccountMapper, null, null,
                null, null, null, null, null);
        ReflectionTestUtils.setField(service, "baseMapper", adminUserMapper);
    }

    @Test
    void onlyAdminCanChangeAccess() {
        try (MockedStatic<StpUtil> login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginId).thenReturn("42");
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.setAiBookkeepingAccess("target", true));
            assertEquals(ErrorCode.FORBIDDEN, error.getErrorCode());
            verifyNoInteractions(userAccountMapper, operationLogMapper);
        }
    }

    @Test
    void grantAndNoOpWriteOneAuditRow() {
        AdminUser admin = new AdminUser();
        admin.setId(2L);
        admin.setStatus(1);
        UserAccount user = new UserAccount();
        user.setId(7L);
        user.setUuid("target");
        user.setAiBookkeepingEnabled(false);
        when(adminUserMapper.selectById(2L)).thenReturn(admin);
        when(userAccountMapper.selectOne(any(Wrapper.class))).thenReturn(user);
        when(userAccountMapper.update(isNull(), any())).thenReturn(1);
        try (MockedStatic<StpUtil> login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginId).thenReturn("admin:2");
            service.setAiBookkeepingAccess("target", true);
            service.setAiBookkeepingAccess("target", true);
        }
        verify(userAccountMapper, never()).updateById(any(UserAccount.class));
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<UserAccount>> update = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(userAccountMapper).update(isNull(), update.capture());
        assertTrue(update.getValue().getSqlSet().contains("ai_bookkeeping_enabled"));
        assertFalse(update.getValue().getSqlSet().contains("nickname"));
        assertFalse(update.getValue().getSqlSet().contains("version"));
        ArgumentCaptor<AdminOperationLog> log = ArgumentCaptor.forClass(AdminOperationLog.class);
        verify(operationLogMapper, times(1)).insert(log.capture());
        assertEquals("ai_bookkeeping_grant", log.getValue().getAction());
        assertEquals("target", log.getValue().getTargetUuid());
    }

    @Test
    void missingUserIsNotFound() {
        AdminUser admin = new AdminUser();
        admin.setId(2L);
        admin.setStatus(1);
        when(adminUserMapper.selectById(2L)).thenReturn(admin);
        try (MockedStatic<StpUtil> login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginId).thenReturn("admin:2");
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.setAiBookkeepingAccess("missing", true));
            assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
        }
        verifyNoInteractions(operationLogMapper);
    }
}
