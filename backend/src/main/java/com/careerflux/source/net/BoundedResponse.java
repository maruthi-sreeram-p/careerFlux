package com.careerflux.source.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * One GET whose body is read with a ceiling enforced while reading.
 *
 * <p>{@code toEntity(byte[].class)} reads the whole body into memory and only
 * then lets the caller look at its size, so a ceiling checked afterwards stops
 * nothing: a host that streams an endless chunked response, with no
 * Content-Length to refuse up front, holds the heap until the read timeout.
 * Discovery fetches pages on domains an operator typed, which makes that host
 * easy to point at. Here the stream is read at most one byte past the ceiling
 * and abandoned the moment it gets there.
 *
 * <p><b>Compression.</b> Nothing is decompressed. The transport is
 * {@code HttpURLConnection}, which neither asks for nor inflates gzip, so the
 * bytes counted are the bytes held; a small compressed body cannot expand past
 * the ceiling because it is never expanded. Adding decompression would need its
 * own bound on the inflated size.
 *
 * <p>Redirects are not followed here and URLs are not validated here: the
 * transport validates every request it creates, and callers walk redirects
 * themselves so each hop is revalidated. This class only bounds the read.
 */
public final class BoundedResponse {

    private static final int BUFFER = 8 * 1024;

    private BoundedResponse() {
    }

    /** What came back. The body is empty, not null, for a redirect or an error status. */
    public record Response(int status, HttpHeaders headers, byte[] body) {

        public boolean isRedirect() {
            return status >= 300 && status < 400;
        }

        public boolean isError() {
            return status >= 400;
        }
    }

    /**
     * What came back when only the start of the body is wanted.
     *
     * @param body      at most the ceiling's worth of bytes; empty, not null, for an error status
     * @param truncated true when the body went on past the ceiling and the rest was never read
     */
    public record Prefix(int status, HttpHeaders headers, byte[] body, boolean truncated) {
    }

    /** The body was larger than the caller allows. Nothing of it is kept. */
    public static final class BodyTooLargeException extends RuntimeException {

        private final long limit;

        BodyTooLargeException(long limit, String detail) {
            super(detail);
            this.limit = limit;
        }

        public long limit() {
            return limit;
        }
    }

    /**
     * Performs the GET and reads the body with the ceiling applied.
     *
     * <p>The body of a redirect or an error status is not read at all: no caller
     * uses it, and reading it would be one more place to be sent a large one.
     *
     * @throws BodyTooLargeException when the declared or actual body exceeds {@code maxBytes}
     */
    public static Response get(RestClient client, String url, int maxBytes) {
        return client.get()
                .uri(url)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    HttpHeaders headers = new HttpHeaders();
                    headers.putAll(response.getHeaders());
                    InputStream body = response.getBody();
                    if (status >= 300) {
                        abandon(body);
                        return new Response(status, headers, new byte[0]);
                    }
                    long declared = headers.getContentLength();
                    if (declared > maxBytes) {
                        abandon(body);
                        throw new BodyTooLargeException(maxBytes, "Declared a body of " + declared
                                + " bytes, above the " + maxBytes + " byte ceiling.");
                    }
                    try {
                        return new Response(status, headers, readAtMost(body, maxBytes));
                    } catch (BodyTooLargeException tooLarge) {
                        abandon(body);
                        throw tooLarge;
                    }
                });
    }

    /**
     * Performs the GET and keeps at most {@code maxBytes} of the body, for a caller
     * that uses the start of a large body rather than refusing it.
     *
     * <p>robots.txt is the case in point: RFC 9309 has a crawler parse at least
     * the first 500 KiB and ignore the rest, so an oversized file is cut at the
     * ceiling, not refused. The rest is never read. One byte past the ceiling is
     * enough to show the body goes on, and the stream is closed there.
     *
     * <p>An error status's body is not read. A redirect's body is, within the
     * same ceiling, because a caller may treat it as content; nothing is
     * followed here.
     */
    public static Prefix getPrefix(RestClient client, String url, int maxBytes) {
        return client.get()
                .uri(url)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    HttpHeaders headers = new HttpHeaders();
                    headers.putAll(response.getHeaders());
                    InputStream body = response.getBody();
                    if (status >= 400) {
                        abandon(body);
                        return new Prefix(status, headers, new byte[0], false);
                    }
                    byte[] read = readPrefix(body, maxBytes + 1);
                    if (read.length <= maxBytes) {
                        return new Prefix(status, headers, read, false);
                    }
                    abandon(body);
                    return new Prefix(status, headers, Arrays.copyOf(read, maxBytes), true);
                });
    }

    /**
     * Closes a body that will not be read to its end.
     *
     * <p>Necessary, not tidy: when the exchange finishes, Spring's
     * {@code SimpleClientHttpResponse.close()} drains whatever is left of the
     * body so the connection can be reused. On an endless body that drain never
     * finishes — nothing accumulates, but the thread is held until the host
     * stops sending. Closing the stream first makes the drain fail at once, and
     * Spring ignores that failure; the connection is dropped rather than reused.
     */
    private static void abandon(InputStream body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (IOException ignored) {
            // Already unusable, which is the point.
        }
    }

    /**
     * Reads the stream to its end, or throws as soon as it has produced more
     * than {@code maxBytes}. Never reads more than {@code maxBytes + 1} bytes.
     */
    static byte[] readAtMost(InputStream in, int maxBytes) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, BUFFER));
        byte[] buffer = new byte[BUFFER];
        long total = 0;
        while (true) {
            // Never ask for more than one byte past the ceiling in total.
            int want = (int) Math.min(buffer.length, (long) maxBytes + 1 - total);
            int read = in.read(buffer, 0, want);
            if (read < 0) {
                return out.toByteArray();
            }
            total += read;
            if (total > maxBytes) {
                throw new BodyTooLargeException(maxBytes,
                        "Body exceeded the " + maxBytes + " byte ceiling while being read.");
            }
            out.write(buffer, 0, read);
        }
    }

    /**
     * Reads until the stream ends or {@code limit} bytes have arrived, whichever
     * is first. Never asks the stream for more than {@code limit} bytes in total.
     */
    static byte[] readPrefix(InputStream in, int limit) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, BUFFER));
        byte[] buffer = new byte[BUFFER];
        long total = 0;
        while (total < limit) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, limit - total));
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }
}
