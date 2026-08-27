package com.trustedrouter.receipts;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Pass-through response stream that retains exact SSE wire bytes and discovers its receipt.
 * Callers must read through this wrapper rather than the original stream.
 */
public final class ReceiptCapture extends FilterInputStream {
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private JsonObject receipt;

    public ReceiptCapture(InputStream source) {
        super(source);
        if (source == null) { throw new NullPointerException("source"); }
    }

    @Override public int read() throws IOException {
        int value = in.read();
        if (value >= 0) {
            captured.write(value);
            if (value == '\n') { refreshReceipt(); }
        }
        return value;
    }

    @Override public int read(byte[] value, int offset, int length) throws IOException {
        int count = in.read(value, offset, length);
        if (count > 0) {
            captured.write(value, offset, count);
            refreshReceipt();
        }
        return count;
    }

    /** Returns a defensive copy of the flattened receipt, or null until one is captured. */
    public JsonObject getReceipt() {
        return receipt == null ? null : receipt.deepCopy();
    }

    /** Returns all bytes read through this wrapper without normalization. */
    public byte[] getCapturedBytes() {
        return captured.toByteArray();
    }

    /** Verify the discovered receipt against every byte read through this wrapper. */
    public ReceiptClaims verify(ReceiptVerificationOptions options)
            throws ReceiptVerificationException {
        if (options == null) { throw new NullPointerException("options"); }
        refreshReceipt();
        if (receipt == null) {
            throw new ReceiptStructureException(
                    "receipt capture check failed: no flattened receipt event has been captured");
        }
        ReceiptVerificationOptions checked = options.toBuilder()
                .responseBody((byte[]) null)
                .responseStream(captured.toByteArray())
                .build();
        return ReceiptVerifier.verifyReceipt(receipt, checked);
    }

    private void refreshReceipt() {
        JsonObject discovered = ReceiptVerifier.discoverReceipt(captured.toByteArray());
        if (discovered != null) { receipt = discovered; }
    }
}
