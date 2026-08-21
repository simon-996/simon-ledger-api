package com.simon.ledger.common.exception;

import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.dto.resp.ConflictResp;

public class VersionConflictException extends BusinessException {
    public VersionConflictException(ConflictResp conflict) {
        super(ErrorCode.CONFLICT, "数据已被其他设备修改", conflict);
    }
}
