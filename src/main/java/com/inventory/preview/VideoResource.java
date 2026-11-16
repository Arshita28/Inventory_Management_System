package com.inventory.preview;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** Byte-range delivery of the single allowlisted intro clip, including Safari seeking. */
final class VideoResource {
    private VideoResource() { }

    static void respond(HttpExchange exchange) throws IOException {
        try (var input = VideoResource.class.getResourceAsStream("/assets/landing-intro.mp4")) {
            if (input == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] data = input.readAllBytes();
            var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "video/mp4");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Cache-Control", "private, max-age=3600");
            headers.set("Accept-Ranges", "bytes");
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range == null) {
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
                return;
            }
            long start;
            long end;
            try {
                if (!range.matches("bytes=\\d*-\\d*")) throw new IllegalArgumentException();
                String[] bounds = range.substring(6).split("-", -1);
                if (bounds[0].isEmpty()) {
                    long suffix = Long.parseLong(bounds[1]);
                    if (suffix <= 0) throw new IllegalArgumentException();
                    start = Math.max(0, data.length - suffix);
                    end = data.length - 1L;
                } else {
                    start = Long.parseLong(bounds[0]);
                    end = bounds[1].isEmpty() ? data.length - 1L : Math.min(Long.parseLong(bounds[1]), data.length - 1L);
                }
                if (start > end || start >= data.length) throw new IllegalArgumentException();
            } catch (IllegalArgumentException exception) {
                headers.set("Content-Range", "bytes */" + data.length);
                exchange.sendResponseHeaders(416, -1);
                return;
            }
            int length = (int) (end - start + 1);
            headers.set("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
            exchange.sendResponseHeaders(206, length);
            exchange.getResponseBody().write(data, (int) start, length);
        }
    }
}
