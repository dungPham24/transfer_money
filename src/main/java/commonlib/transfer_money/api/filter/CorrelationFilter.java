package commonlib.transfer_money.api.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns a unique requestId to every incoming HTTP request and stores it in
 * SLF4J MDC so that every log line produced during that request automatically
 * includes the field — no need to thread it through method signatures.
 *
 * Clients can supply their own id via the X-Request-ID header (useful for
 * correlating client-side and server-side traces).  The same id is echoed back
 * in the response header so callers can reference it when filing support issues.
 *
 * Order.HIGHEST_PRECEDENCE ensures MDC is populated before Spring Security,
 * logging filters, or any other component touches the request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter implements Filter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    static final String MDC_REQUEST_ID = "requestId";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest)  req;
        HttpServletResponse response = (HttpServletResponse) res;

        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            chain.doFilter(req, res);
        } finally {
            // Always clear — MDC is thread-local and threads are reused in the pool.
            MDC.clear();
        }
    }
}