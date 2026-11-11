package co.edu.corhuila.synkro.customers.application.port.in;

import co.edu.corhuila.synkro.customers.domain.model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public interface CustomerUseCases {
    Created create(Caller caller, String key, CustomerFields fields);
    Customer get(Caller caller, UUID id);
    Page list(Caller caller, Query query);
    Customer update(Caller caller, UUID id, CustomerFields fields);
    Customer deactivate(Caller caller, UUID id);
    /** Bounded name/document search for selectors (HU-CLI-05). Never returns more than {@link SearchResult#LIMIT}. */
    SearchResult search(Caller caller, Search search);
    /** ADMIN-only reactivation of an inactive customer; repeating it is safe (HU-CLI-05). */
    Customer activate(Caller caller, UUID id, String correlationId);
    record Created(Customer customer, boolean created) {}
    record Page(List<Customer> data, long total, int page, int limit) {
        public Page { data = List.copyOf(data); }
        public long totalPages() { return (total + limit - 1) / limit; }
    }
    record Query(String identityDocument, Boolean active, int page, int limit) {
        public Query {
            if (identityDocument != null) CustomerFields.length(identityDocument, "identityDocument", 30, true);
            if (page < 1) throw CustomerFailure.invalid("page");
            if (limit < 1 || limit > 100) throw CustomerFailure.invalid("limit");
        }
        public long offset() { return ((long) page - 1) * limit; }
    }

    /** A validated search: trimmed text of 2-100 code points, its whitespace-separated tokens and the status scope. */
    record Search(String text, List<String> tokens, Status status) {
        public static final int MIN = 2, MAX = 100;
        public enum Status { ACTIVE, INACTIVE }
        public Search { tokens = List.copyOf(tokens); }
        /** @param rawText the body's query; @param rawStatus the body's status, null meaning ACTIVE */
        public static Search of(String rawText, String rawStatus) {
            String text = trim(rawText);
            int length = text.codePointCount(0, text.length());
            if (rawText == null || length < MIN || length > MAX) throw CustomerFailure.invalid("query", "invalid_length");
            Status status = Status.ACTIVE;
            if (rawStatus != null) {
                try { status = Status.valueOf(rawStatus); }
                catch (IllegalArgumentException e) { throw CustomerFailure.invalid("status", "invalid_value"); }
                if (!status.name().equals(rawStatus)) throw CustomerFailure.invalid("status", "invalid_value");
            }
            var tokens = new ArrayList<String>();
            for (String token : text.split("[\\s\\p{Z}\\p{Cc}\\p{Cf}]+")) if (!token.isEmpty()) tokens.add(token);
            return new Search(text, tokens, status);
        }
        private static String trim(String raw) {
            if (raw == null) return "";
            int start = 0, end = raw.length();
            while (start < end && ContactRules.blank(raw.substring(raw.offsetByCodePoints(start, 0), raw.offsetByCodePoints(start, 1)))) start = raw.offsetByCodePoints(start, 1);
            while (end > start && ContactRules.blank(raw.substring(raw.offsetByCodePoints(end, -1), end))) end = raw.offsetByCodePoints(end, -1);
            return raw.substring(start, end);
        }
    }
    /** What a selector needs and nothing else: no email, phone or address. */
    record Suggestion(UUID customerId, String name, String identityDocument, boolean active, Match match) {
        public enum Match { DOCUMENT_EXACT, DOCUMENT_PREFIX, NAME }
    }
    record SearchResult(List<Suggestion> data, boolean hasMore) {
        public static final int LIMIT = 5;
        public SearchResult {
            if (data.size() > LIMIT) throw new IllegalArgumentException("A search never returns more than " + LIMIT + " suggestions");
            data = List.copyOf(data);
        }
    }
}
