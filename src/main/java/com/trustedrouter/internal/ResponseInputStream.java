package com.trustedrouter.internal;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import okhttp3.Response;

/** Input stream that owns and closes its OkHttp response. */
public final class ResponseInputStream extends FilterInputStream {
    private final Response response;
    public ResponseInputStream(Response response, InputStream input) {
        super(input);
        this.response = response;
    }
    @Override public void close() throws IOException {
        try { super.close(); } finally { response.close(); }
    }
}
