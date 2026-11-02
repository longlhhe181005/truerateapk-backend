package vn.truerate.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Protects the AI key from abuse: fixed window per client address on the analyze endpoint. */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private record Window(long minute, AtomicInteger count) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int limit;

    public RateLimitFilter(@Value("${truerate.rate-limit-per-minute:30}") int limit) {
        this.limit = limit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // browsers send an OPTIONS check before the real call; it must not use up the user's limit
        return "OPTIONS".equals(request.getMethod())
                || (!uri.equals("/api/analyze") && !uri.equals("/api/ocr") && !uri.equals("/api/kit"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long minute = System.currentTimeMillis() / 60_000L;
        boolean isOcr = req.getRequestURI().equals("/api/ocr") || req.getRequestURI().equals("/api/kit");
        Window w = windows.compute(req.getRemoteAddr() + (isOcr ? "|ocr" : ""), (k, old) ->
                old == null || old.minute() != minute ? new Window(minute, new AtomicInteger(0)) : old);
        if (w.count().incrementAndGet() > (isOcr ? Math.max(3, limit / 3) : limit)) {
            res.setStatus(429);
            res.setContentType("application/json;charset=UTF-8");
            res.getOutputStream().write("{\"errors\":[{\"field\":\"form\",\"message\":\"Bạn thao tác quá nhanh. Hãy thử lại sau một phút.\"}]}"
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (windows.size() > 10_000) {
            windows.clear();
        }
        chain.doFilter(req, res);
    }
}
