package com.simon.ledger.service.impl;

import com.simon.ledger.dto.req.*;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReviewInputRegressionTests {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test void serviceRejectsPhoneShapedEmailBeforeSavingAccount() {
        var users = org.mockito.Mockito.mock(com.simon.ledger.mapper.UserAccountMapper.class);
        var service = new AuthServiceImpl(null,null,null,org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"baseMapper",users);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"entityClass",com.simon.ledger.entity.UserAccount.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"mapperClass",com.simon.ledger.mapper.UserAccountMapper.class);
        if (com.baomidou.mybatisplus.core.metadata.TableInfoHelper.getTableInfo(com.simon.ledger.entity.UserAccount.class) == null)
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),com.simon.ledger.entity.UserAccount.class);
        AuthRegisterReq request = new AuthRegisterReq(); request.setEmail("13800138000"); request.setNickname("new"); request.setPassword("password");
        var error = assertThrows(com.simon.ledger.common.exception.BusinessException.class, () -> service.register(request));
        assertEquals(com.simon.ledger.common.ErrorCode.BAD_REQUEST,error.getErrorCode());
        org.mockito.Mockito.verifyNoInteractions(users);
    }

    @Test void duplicateAccountRaceReturnsValidationErrorInsteadOfDatabaseError() {
        var users = org.mockito.Mockito.mock(com.simon.ledger.mapper.UserAccountMapper.class);
        var service = new AuthServiceImpl(null,null,null,org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"baseMapper",users);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"entityClass",com.simon.ledger.entity.UserAccount.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"mapperClass",com.simon.ledger.mapper.UserAccountMapper.class);
        if (com.baomidou.mybatisplus.core.metadata.TableInfoHelper.getTableInfo(com.simon.ledger.entity.UserAccount.class) == null)
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),com.simon.ledger.entity.UserAccount.class);
        org.mockito.Mockito.when(users.insert(org.mockito.ArgumentMatchers.any(com.simon.ledger.entity.UserAccount.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("racing registration"));
        AuthRegisterReq request = new AuthRegisterReq(); request.setEmail("new@example.test"); request.setNickname("new"); request.setPassword("password");
        var error = assertThrows(com.simon.ledger.common.exception.BusinessException.class, () -> service.register(request));
        assertEquals(com.simon.ledger.common.ErrorCode.BAD_REQUEST,error.getErrorCode());
    }

    @Test void emailCannotBeUsedAsPhone() {
        AuthRegisterReq request = new AuthRegisterReq();
        request.setPhone("victim@example.com"); request.setNickname("name"); request.setPassword("123456");
        assertTrue(validator.validate(request).stream().anyMatch(v -> v.getPropertyPath().toString().equals("phone")));
    }
    @Test void amountRejectsExtraFractionAndIntegerOverflow() {
        TransactionCreateReq request = new TransactionCreateReq();
        request.setType(0); request.setCurrencyCode("CNY"); request.setCategory("food");
        request.setHappenedAt(LocalDateTime.now()); request.setClientOperationId("operation"); request.setPersonUuids(List.of("person"));
        for (String value : List.of("1.001", "10000000000000000.00")) {
            request.setAmount(new BigDecimal(value));
            assertTrue(validator.validate(request).stream().anyMatch(v -> v.getPropertyPath().toString().equals("amount")), value);
        }
        request.setAmount(new BigDecimal("9999999999999999.99"));
        assertTrue(validator.validate(request).isEmpty());
    }
    @Test void personAcceptsSameLongAvatarAsAccount() {
        PersonCreateReq request = new PersonCreateReq(); request.setName("name"); request.setAvatar("https://example.test/" + "a".repeat(180));
        assertTrue(validator.validate(request).isEmpty());
    }
}
