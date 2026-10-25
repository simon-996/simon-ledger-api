package com.simon.ledger.config.mybatis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import java.math.BigDecimal;
import java.sql.*;
import java.util.Map;

/** Preserve BigDecimal values on both entity inserts and MyBatis reads. */
public class CurrencyRatesTypeHandler extends BaseTypeHandler<Map<String, BigDecimal>> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, BigDecimal>> TYPE = new TypeReference<>() {};
    public static String toJson(Map<String, BigDecimal> value) {
        try {
            // MySQL parses JSON fractional numbers as binary doubles. Decimal strings
            // keep the full DECIMAL(18,8) range exact; API DTOs still emit numbers.
            Map<String, String> exact = new java.util.LinkedHashMap<>();
            value.forEach((code, rate) -> exact.put(code, rate.toPlainString()));
            return JSON.writeValueAsString(exact);
        }
        catch (Exception error) { throw new IllegalArgumentException("Invalid currency rates", error); }
    }
    @Override public void setNonNullParameter(PreparedStatement ps, int i, Map<String, BigDecimal> value, JdbcType type) throws SQLException { ps.setString(i, toJson(value)); }
    @Override public Map<String, BigDecimal> getNullableResult(ResultSet rs, String name) throws SQLException { return read(rs.getString(name)); }
    @Override public Map<String, BigDecimal> getNullableResult(ResultSet rs, int index) throws SQLException { return read(rs.getString(index)); }
    @Override public Map<String, BigDecimal> getNullableResult(CallableStatement cs, int index) throws SQLException { return read(cs.getString(index)); }
    private Map<String, BigDecimal> read(String value) throws SQLException {
        if (value == null) return null;
        try { return JSON.readValue(value, TYPE); }
        catch (Exception error) { throw new SQLException("Invalid stored currency rates", error); }
    }
}
