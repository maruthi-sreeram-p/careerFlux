package com.careerflux.source.net;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * The transport every outbound source request goes through.
 *
 * <p>Two jobs, both of which have to happen below the level of any individual
 * caller, because a check a caller can forget is not a control.
 *
 * <ol>
 *   <li><b>Validate the destination.</b> {@link SafeUrlValidator} runs on every
 *       request this factory creates. A call site that forgets to validate still
 *       cannot reach a private address, and a URL that only becomes dangerous
 *       later — a hostname whose DNS answer changed — is caught on the next
 *       request rather than trusted because it passed once at registration.
 *   <li><b>Refuse to follow redirects invisibly.</b> {@code HttpURLConnection}
 *       follows 3xx by default and does it inside the connection, where nothing
 *       above can inspect where it went. That turns one validated request into
 *       an unvalidated one: a public host answering {@code 302 Location:
 *       http://169.254.169.254/} defeats every check above. Following is
 *       therefore switched off here and done explicitly, one validated hop at a
 *       time, by the callers that legitimately need it.
 * </ol>
 */
public class SafeClientHttpRequestFactory extends SimpleClientHttpRequestFactory {

    private final SafeUrlValidator validator;

    public SafeClientHttpRequestFactory(SafeUrlValidator validator) {
        this.validator = validator;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
        // Throws UnsafeUrlException, which callers translate into whatever their
        // layer reports — an AdapterException for a fetch, a 400 for a request.
        validator.validate(uri.toString());
        return super.createRequest(uri, httpMethod);
    }

    @Override
    protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
        super.prepareConnection(connection, httpMethod);
        connection.setInstanceFollowRedirects(false);
    }
}
