package commonlib.transfer_money.application;

import java.util.List;

/**
 * Framework-agnostic page wrapper returned by use cases.
 * Infrastructure adapters convert Spring's Page<T> into this type.
 */
public record PageResult<T>(
        List<T> content,
        long totalElements,
        int totalPages,
        int page,
        int size
) {}