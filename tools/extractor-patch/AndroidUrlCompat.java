package org.schabi.newpipe.extractor.utils;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;

/**
 * Old-Android compatibility shim injected into the patched NewPipeExtractor jar.
 *
 * <p>URLDecoder.decode(String, Charset) and URLEncoder.encode(String, Charset) are Java 10
 * APIs that only exist on Android from API level 33. NewPipeExtractor v0.26.5 calls the
 * Charset overloads (e.g. Utils.decodeUrlUtf8), which crashes with NoSuchMethodError on
 * Android 10 (API 29) and below. Core library desugaring does not cover java.net.</p>
 *
 * <p>The patch tool rewrites every such call site to these helpers, which use the
 * decode/encode(String, String) overloads available since API level 1. "UTF-8" is
 * guaranteed to be supported on every Android version, hence the AssertionError path is
 * unreachable. Behavior is otherwise identical: IllegalArgumentException on malformed
 * input, same '+' handling.</p>
 */
public final class AndroidUrlCompat {

    private AndroidUrlCompat() {
    }

    public static String decodeUtf8(final String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            // "UTF-8" is required to be supported on every platform; cannot happen.
            throw new AssertionError(e);
        }
    }

    public static String encodeUtf8(final String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
