package com.simon.ledger.dto.resp;

import lombok.Data;

import java.util.List;

@Data
public class LedgerCreateWithPeopleResp {

    private LedgerResp ledger;

    private List<PersonResp> people;
}
