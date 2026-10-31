package com.articulorum.platform.storage;

import java.util.List;

/**
 * Outcome of validating an OCFL object, including content fixity.
 *
 * @param valid    {@code true} when no error was found
 * @param errors   descriptions of the errors found
 * @param warnings descriptions of the warnings found
 */
public record ValidationReport(boolean valid, List<String> errors, List<String> warnings) {
}
