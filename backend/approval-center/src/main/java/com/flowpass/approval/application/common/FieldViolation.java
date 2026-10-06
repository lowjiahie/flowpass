package com.flowpass.approval.application.common;

/**
 * One reason a request was refused, tied to the input field it is about.
 *
 * @param field   path of the offending field (e.g. {@code amount}, {@code items[0].qty}); empty for the whole input
 * @param message what is wrong, in plain words
 */
public record FieldViolation(String field, String message) {
}
