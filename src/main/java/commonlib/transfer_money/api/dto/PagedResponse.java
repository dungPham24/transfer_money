package commonlib.transfer_money.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Generic paginated response wrapper")
public record PagedResponse<T>(

        @Schema(description = "Items on the current page")
        List<T> content,

        @Schema(description = "Total number of items across all pages", example = "42")
        long totalElements,

        @Schema(description = "Total number of pages (ceil(totalElements / size))", example = "3")
        int totalPages,

        @Schema(description = "Zero-based current page index", example = "0")
        int page,

        @Schema(description = "Requested page size", example = "20")
        int size

) {}