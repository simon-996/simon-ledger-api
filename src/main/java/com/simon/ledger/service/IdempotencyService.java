package com.simon.ledger.service;

import java.util.function.Supplier;

public interface IdempotencyService {

    <T> T execute(String key, String method, String path, Class<T> responseType, Supplier<T> supplier);

    void executeVoid(String key, String method, String path, Runnable runnable);
}
