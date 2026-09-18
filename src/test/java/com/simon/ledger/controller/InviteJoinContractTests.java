package com.simon.ledger.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.ledger.common.Result;
import com.simon.ledger.dto.resp.InviteJoinResp;
import com.simon.ledger.dto.resp.InviteResp;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InviteJoinContractTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void joinResponseContainsNestedAdmissionAndLegacyInvitationFields() throws Exception {
        InviteResp invite = new InviteResp();
        invite.setCode("INVITE01");
        invite.setLedgerUuid("ledger-uuid");
        invite.setRole("editor");

        InviteJoinResp response = new InviteJoinResp();
        response.setCode(invite.getCode());
        response.setLedgerUuid(invite.getLedgerUuid());
        response.setRole(invite.getRole());
        response.setInvite(invite);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));
        assertEquals("INVITE01", json.get("code").asText());
        assertEquals("ledger-uuid", json.get("ledgerUuid").asText());
        assertEquals("editor", json.get("role").asText());
        assertEquals("INVITE01", json.get("invite").get("code").asText());
    }

    @Test
    void legacyFlatIdempotencyPayloadStillDeserializesAsJoinResponse() throws Exception {
        InviteJoinResp response = objectMapper.readValue(
                "{\"code\":\"INVITE01\",\"ledgerUuid\":\"ledger-uuid\",\"role\":\"editor\"}",
                InviteJoinResp.class
        );

        assertEquals("INVITE01", response.getCode());
        assertEquals("ledger-uuid", response.getLedgerUuid());
        assertEquals("editor", response.getRole());
        assertEquals(null, response.getInvite());
    }

    @Test
    void joinControllerExposesTheNestedResponseTypeOnTheJoinPath() throws Exception {
        Method join = InviteController.class.getMethod("join", String.class, String.class);
        PostMapping mapping = join.getAnnotation(PostMapping.class);
        assertNotNull(mapping);
        assertTrue(java.util.Arrays.asList(mapping.value()).contains("/api/invites/{code}/join"));
        ParameterizedType resultType = (ParameterizedType) join.getGenericReturnType();
        assertEquals(Result.class, resultType.getRawType());
        assertEquals(InviteJoinResp.class, resultType.getActualTypeArguments()[0]);
    }
}
