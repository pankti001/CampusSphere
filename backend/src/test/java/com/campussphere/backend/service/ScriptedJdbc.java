package com.campussphere.backend.service;

import java.util.*;
import java.util.function.Function;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

/**
 * A JdbcTemplate stand-in for service tests: answers each statement from the first rule whose SQL fragment it
 * contains, and records every statement with its parameters so tests can check scoping and what was written.
 * No database is used. Unmatched queries return no rows, null or 1 updated row.
 */
class ScriptedJdbc extends JdbcTemplate {
    record Call(String sql, List<Object> args) {}

    private final List<Map.Entry<String, Function<List<Object>, Object>>> rules = new ArrayList<>();
    final List<Call> calls = new ArrayList<>();

    ScriptedJdbc on(String fragment, Object result) {
        return answer(fragment, args -> result);
    }

    ScriptedJdbc answer(String fragment, Function<List<Object>, Object> result) {
        rules.add(Map.entry(fragment, result));
        return this;
    }

    private Object reply(String sql, Object[] args) {
        List<Object> values = args == null ? List.of() : Arrays.asList(args);
        calls.add(new Call(sql, values));
        for (var rule : rules) if (sql.contains(rule.getKey())) return rule.getValue().apply(values);
        return null;
    }

    List<Call> calls(String fragment) {
        return calls.stream().filter(c -> c.sql().contains(fragment)).toList();
    }

    boolean ran(String fragment) {
        return !calls(fragment).isEmpty();
    }

    @Override @SuppressWarnings("unchecked")
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        Object result = reply(sql, args);
        return result == null ? List.of() : (List<T>) result;
    }

    @Override
    public void query(String sql, RowCallbackHandler handler, Object... args) {
        reply(sql, args);
    }

    @Override @SuppressWarnings("unchecked")
    public <T> T queryForObject(String sql, Class<T> type, Object... args) {
        return (T) reply(sql, args);
    }

    @Override @SuppressWarnings("unchecked")
    public <T> T queryForObject(String sql, RowMapper<T> mapper, Object... args) {
        return (T) reply(sql, args);
    }

    @Override
    public int update(String sql, Object... args) {
        Object result = reply(sql, args);
        return result == null ? 1 : (Integer) result;
    }
}
