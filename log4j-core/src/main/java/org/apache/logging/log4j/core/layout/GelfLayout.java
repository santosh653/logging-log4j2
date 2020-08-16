/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.core.layout;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.internal.ExcludeChecker;
import org.apache.logging.log4j.core.layout.internal.IncludeChecker;
import org.apache.logging.log4j.core.layout.internal.ListChecker;
import org.apache.logging.log4j.core.lookup.StrSubstitutor;
import org.apache.logging.log4j.core.net.Severity;
import org.apache.logging.log4j.core.util.JsonUtils;
import org.apache.logging.log4j.core.util.KeyValuePair;
import org.apache.logging.log4j.core.util.NetUtils;
import org.apache.logging.log4j.core.util.Patterns;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.plugins.Node;
import org.apache.logging.log4j.plugins.Plugin;
import org.apache.logging.log4j.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.plugins.PluginElement;
import org.apache.logging.log4j.plugins.PluginFactory;
import org.apache.logging.log4j.status.StatusLogger;
import org.apache.logging.log4j.util.StringBuilderFormattable;
import org.apache.logging.log4j.util.Strings;
import org.apache.logging.log4j.util.TriConsumer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Lays out events in the Graylog Extended Log Format (GELF) 1.1.
 * <p>
 * This layout compresses JSON to GZIP or ZLIB (the {@code compressionType}) if
 * log event data is larger than 1024 bytes (the {@code compressionThreshold}).
 * This layout does not implement chunking.
 * </p>
 *
 * @see <a href="http://docs.graylog.org/en/latest/pages/gelf.html#gelf">GELF specification</a>
 */
@Plugin(name = "GelfLayout", category = Node.CATEGORY, elementType = Layout.ELEMENT_TYPE, printObject = true)
public final class GelfLayout extends AbstractStringLayout {

    public enum CompressionType {

        GZIP {
            @Override
            public DeflaterOutputStream createDeflaterOutputStream(final OutputStream os) throws IOException {
                return new GZIPOutputStream(os);
            }
        },
        ZLIB {
            @Override
            public DeflaterOutputStream createDeflaterOutputStream(final OutputStream os) throws IOException {
                return new DeflaterOutputStream(os);
            }
        },
        OFF {
            @Override
            public DeflaterOutputStream createDeflaterOutputStream(final OutputStream os) throws IOException {
                return null;
            }
        };

        public abstract DeflaterOutputStream createDeflaterOutputStream(OutputStream os) throws IOException;
    }

    private static final char C = ',';
    private static final int COMPRESSION_THRESHOLD = 1024;
    private static final char Q = '\"';
    private static final String QC = "\",";
    private static final String QU = "\"_";

    private final KeyValuePair[] additionalFields;
    private final int compressionThreshold;
    private final CompressionType compressionType;
    private final String host;
    private final boolean includeStacktrace;
    private final boolean includeThreadContext;
    private final boolean includeNullDelimiter;
    private final boolean includeNewLineDelimiter;
    private final PatternLayout layout;
    private final FieldWriter fieldWriter;

    public static class Builder<B extends Builder<B>> extends AbstractStringLayout.Builder<B>
        implements org.apache.logging.log4j.plugins.util.Builder<GelfLayout> {

        @PluginBuilderAttribute
        private String host;

        @PluginElement("AdditionalField")
        private KeyValuePair[] additionalFields;

        @PluginBuilderAttribute
        private CompressionType compressionType = CompressionType.GZIP;

        @PluginBuilderAttribute
        private int compressionThreshold = COMPRESSION_THRESHOLD;

        @PluginBuilderAttribute
        private boolean includeStacktrace = true;

        @PluginBuilderAttribute
        private boolean includeThreadContext = true;

        @PluginBuilderAttribute
        private boolean includeNullDelimiter = false;

        @PluginBuilderAttribute
        private boolean includeNewLineDelimiter = false;

        @PluginBuilderAttribute
        private String threadContextIncludes = null;

        @PluginBuilderAttribute
        private String threadContextExcludes = null;

        @PluginBuilderAttribute
        private String messagePattern = null;

        public Builder() {
            super();
            setCharset(StandardCharsets.UTF_8);
        }

        @Override
        public GelfLayout build() {
            ListChecker checker = null;
            if (threadContextExcludes != null) {
                final String[] array = threadContextExcludes.split(Patterns.COMMA_SEPARATOR);
                if (array.length > 0) {
                    List<String> excludes = new ArrayList<>(array.length);
                    for (final String str : array) {
                        excludes.add(str.trim());
                    }
                    checker = new ExcludeChecker(excludes);
                }
            }
            if (threadContextIncludes != null) {
                final String[] array = threadContextIncludes.split(Patterns.COMMA_SEPARATOR);
                if (array.length > 0) {
                    List<String> includes = new ArrayList<>(array.length);
                    for (final String str : array) {
                        includes.add(str.trim());
                    }
                    checker = new IncludeChecker(includes);
                }
            }
            if (checker == null) {
                checker = ListChecker.NOOP_CHECKER;
            }
            PatternLayout patternLayout = null;
            if (messagePattern != null) {
                patternLayout = PatternLayout.newBuilder().setPattern(messagePattern)
                        .setAlwaysWriteExceptions(includeStacktrace)
                        .setConfiguration(getConfiguration())
                        .build();
            }
            return new GelfLayout(getConfiguration(), host, additionalFields, compressionType, compressionThreshold,
                    includeStacktrace, includeThreadContext, includeNullDelimiter, includeNewLineDelimiter, checker,
                    patternLayout);
        }

        public String getHost() {
            return host;
        }

        public CompressionType getCompressionType() {
            return compressionType;
        }

        public int getCompressionThreshold() {
            return compressionThreshold;
        }

        public boolean isIncludeStacktrace() {
            return includeStacktrace;
        }

        public boolean isIncludeThreadContext() {
            return includeThreadContext;
        }

        public boolean isIncludeNullDelimiter() { return includeNullDelimiter; }

        public boolean isIncludeNewLineDelimiter() {
            return includeNewLineDelimiter;
        }

        public KeyValuePair[] getAdditionalFields() {
            return additionalFields;
        }

        /**
         * The value of the <code>host</code> property (optional, defaults to local host name).
         *
         * @return this builder
         */
        public B setHost(final String host) {
            this.host = host;
            return asBuilder();
        }

        /**
         * Compression to use (optional, defaults to GZIP).
         *
         * @return this builder
         */
        public B setCompressionType(final CompressionType compressionType) {
            this.compressionType = compressionType;
            return asBuilder();
        }

        /**
         * Compress if data is larger than this number of bytes (optional, defaults to 1024).
         *
         * @return this builder
         */
        public B setCompressionThreshold(final int compressionThreshold) {
            this.compressionThreshold = compressionThreshold;
            return asBuilder();
        }

        /**
         * Whether to include full stacktrace of logged Throwables (optional, default to true).
         * If set to false, only the class name and message of the Throwable will be included.
         *
         * @return this builder
         */
        public B setIncludeStacktrace(final boolean includeStacktrace) {
            this.includeStacktrace = includeStacktrace;
            return asBuilder();
        }

        /**
         * Whether to include thread context as additional fields (optional, default to true).
         *
         * @return this builder
         */
        public B setIncludeThreadContext(final boolean includeThreadContext) {
            this.includeThreadContext = includeThreadContext;
            return asBuilder();
        }

        /**
         * Whether to include NULL byte as delimiter after each event (optional, default to false).
         * Useful for Graylog GELF TCP input.
         *
         * @return this builder
         */
        public B setIncludeNullDelimiter(final boolean includeNullDelimiter) {
            this.includeNullDelimiter = includeNullDelimiter;
            return asBuilder();
        }

        /**
         * Whether to include newline (LF) as delimiter after each event (optional, default to false).
         *
         * @return this builder
         */
        public B setIncludeNewLineDelimiter(final boolean includeNewLineDelimiter) {
            this.includeNewLineDelimiter = includeNewLineDelimiter;
            return asBuilder();
        }

        /**
         * Additional fields to set on each log event.
         *
         * @return this builder
         */
        public B setAdditionalFields(final KeyValuePair[] additionalFields) {
            this.additionalFields = additionalFields;
            return asBuilder();
        }

        /**
         * The pattern to use to format the message.
         * @param pattern the pattern string.
         * @return this builder
         */
        public B setMessagePattern(final String pattern) {
            this.messagePattern = pattern;
            return asBuilder();
        }

        /**
         * A comma separated list of thread context keys to include;
         * @param mdcIncludes the list of keys.
         * @return this builder
         */
        public B setMdcIncludes(final String mdcIncludes) {
            this.threadContextIncludes = mdcIncludes;
            return asBuilder();
        }

        /**
         * A comma separated list of thread context keys to include;
         * @param mdcExcludes the list of keys.
         * @return this builder
         */
        public B setMdcExcludes(final String mdcExcludes) {
            this.threadContextExcludes = mdcExcludes;
            return asBuilder();
        }
    }

    private GelfLayout(final Configuration config, final String host, final KeyValuePair[] additionalFields,
            final CompressionType compressionType, final int compressionThreshold, final boolean includeStacktrace,
            final boolean includeThreadContext, final boolean includeNullDelimiter, final boolean includeNewLineDelimiter,
            final ListChecker listChecker, final PatternLayout patternLayout) {
        super(config, StandardCharsets.UTF_8, null, null);
        this.host = host != null ? host : NetUtils.getLocalHostname();
        this.additionalFields = additionalFields != null ? additionalFields : new KeyValuePair[0];
        if (config == null) {
            for (final KeyValuePair additionalField : this.additionalFields) {
                if (valueNeedsLookup(additionalField.getValue())) {
                    throw new IllegalArgumentException("configuration needs to be set when there are additional fields with variables");
                }
            }
        }
        this.compressionType = compressionType;
        this.compressionThreshold = compressionThreshold;
        this.includeStacktrace = includeStacktrace;
        this.includeThreadContext = includeThreadContext;
        this.includeNullDelimiter = includeNullDelimiter;
        if (includeNullDelimiter && compressionType != CompressionType.OFF) {
            throw new IllegalArgumentException("null delimiter cannot be used with compression");
        }
        this.includeNewLineDelimiter = includeNewLineDelimiter;
        this.fieldWriter = new FieldWriter(listChecker);
        this.layout = patternLayout;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("host=").append(host);
        sb.append(", compressionType=").append(compressionType.toString());
        sb.append(", compressionThreshold=").append(compressionThreshold);
        sb.append(", includeStackTrace=").append(includeStacktrace);
        sb.append(", includeThreadContext=").append(includeThreadContext);
        sb.append(", includeNullDelimiter=").append(includeNullDelimiter);
        sb.append(", includeNewLineDelimiter=").append(includeNewLineDelimiter);
        String threadVars = fieldWriter.getChecker().toString();
        if (threadVars.length() > 0) {
            sb.append(", ").append(threadVars);
        }
        if (layout != null) {
            sb.append(", PatternLayout{").append(layout.toString()).append("}");
        }
        return sb.toString();
    }

    @PluginFactory
    public static <B extends Builder<B>> B newBuilder() {
        return new Builder<B>().asBuilder();
    }

    @Override
    public Map<String, String> getContentFormat() {
        return Collections.emptyMap();
    }

    @Override
    public String getContentType() {
        return "application/json; charset=" + this.getCharset();
    }

    @Override
    public byte[] toByteArray(final LogEvent event) {
        final StringBuilder text = toText(event, getStringBuilder(), false);
        final byte[] bytes = getBytes(text.toString());
        return compressionType != CompressionType.OFF && bytes.length > compressionThreshold ? compress(bytes) : bytes;
    }

    @Override
    public void encode(final LogEvent event, final ByteBufferDestination destination) {
        if (compressionType != CompressionType.OFF) {
            super.encode(event, destination);
            return;
        }
        final StringBuilder text = toText(event, getStringBuilder(), true);
        final Encoder<StringBuilder> helper = getStringBuilderEncoder();
        helper.encode(text, destination);
    }

    @Override
    public boolean requiresLocation() {
        return Objects.nonNull(layout) && layout.requiresLocation();
    }

    private byte[] compress(final byte[] bytes) {
        try {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream(compressionThreshold / 8);
            try (final DeflaterOutputStream stream = compressionType.createDeflaterOutputStream(baos)) {
                if (stream == null) {
                    return bytes;
                }
                stream.write(bytes);
                stream.finish();
            }
            return baos.toByteArray();
        } catch (final IOException e) {
            StatusLogger.getLogger().error(e);
            return bytes;
        }
    }

    @Override
    public String toSerializable(final LogEvent event) {
        final StringBuilder text = toText(event, getStringBuilder(), false);
        return text.toString();
    }

    private StringBuilder toText(final LogEvent event, final StringBuilder builder, final boolean gcFree) {
        builder.append('{');
        builder.append("\"version\":\"1.1\",");
        builder.append("\"host\":\"");
        JsonUtils.quoteAsString(toNullSafeString(host), builder);
        builder.append(QC);
        builder.append("\"timestamp\":").append(formatTimestamp(event.getTimeMillis())).append(C);
        builder.append("\"level\":").append(formatLevel(event.getLevel())).append(C);
        if (event.getThreadName() != null) {
            builder.append("\"_thread\":\"");
            JsonUtils.quoteAsString(event.getThreadName(), builder);
            builder.append(QC);
        }
        if (event.getLoggerName() != null) {
            builder.append("\"_logger\":\"");
            JsonUtils.quoteAsString(event.getLoggerName(), builder);
            builder.append(QC);
        }
        if (additionalFields.length > 0) {
            final StrSubstitutor strSubstitutor = getConfiguration().getStrSubstitutor();
            for (final KeyValuePair additionalField : additionalFields) {
                builder.append(QU);
                JsonUtils.quoteAsString(additionalField.getKey(), builder);
                builder.append("\":\"");
                final String value = valueNeedsLookup(additionalField.getValue())
                    ? strSubstitutor.replace(event, additionalField.getValue())
                    : additionalField.getValue();
                JsonUtils.quoteAsString(toNullSafeString(value), builder);
                builder.append(QC);
            }
        }
        if (includeThreadContext) {
            event.getContextData().forEach(fieldWriter, builder);
        }

        if (event.getThrown() != null || layout != null) {
            builder.append("\"full_message\":\"");
            if (layout != null) {
                final StringBuilder messageBuffer = getMessageStringBuilder();
                layout.serialize(event, messageBuffer);
                JsonUtils.quoteAsString(messageBuffer, builder);
            } else {
                if (includeStacktrace) {
                    JsonUtils.quoteAsString(formatThrowable(event.getThrown()), builder);
                } else {
                    JsonUtils.quoteAsString(event.getThrown().toString(), builder);
                }
            }
            builder.append(QC);
        }

        builder.append("\"short_message\":\"");
        final Message message = event.getMessage();
        if (message instanceof CharSequence) {
            JsonUtils.quoteAsString(((CharSequence) message), builder);
        } else if (gcFree && message instanceof StringBuilderFormattable) {
            final StringBuilder messageBuffer = getMessageStringBuilder();
            try {
                ((StringBuilderFormattable) message).formatTo(messageBuffer);
                JsonUtils.quoteAsString(messageBuffer, builder);
            } finally {
                trimToMaxSize(messageBuffer);
            }
        } else {
            JsonUtils.quoteAsString(toNullSafeString(message.getFormattedMessage()), builder);
        }
        builder.append(Q);
        builder.append('}');
        if (includeNullDelimiter) {
            builder.append('\0');
        }
        if (includeNewLineDelimiter) {
            builder.append('\n');
        }
        return builder;
    }

    private static boolean valueNeedsLookup(final String value) {
        return value != null && value.contains("${");
    }

    private static class FieldWriter implements TriConsumer<String, Object, StringBuilder> {
        private final ListChecker checker;

        FieldWriter(ListChecker checker) {
            this.checker = checker;
        }

        @Override
        public void accept(final String key, final Object value, final StringBuilder stringBuilder) {
            if (checker.check(key)) {
                stringBuilder.append(QU);
                JsonUtils.quoteAsString(key, stringBuilder);
                stringBuilder.append("\":\"");
                JsonUtils.quoteAsString(toNullSafeString(String.valueOf(value)), stringBuilder);
                stringBuilder.append(QC);
            }
        }

        public ListChecker getChecker() {
            return checker;
        }
    };

    private static final ThreadLocal<StringBuilder> messageStringBuilder = new ThreadLocal<>();

    private static StringBuilder getMessageStringBuilder() {
        StringBuilder result = messageStringBuilder.get();
        if (result == null) {
            result = new StringBuilder(DEFAULT_STRING_BUILDER_SIZE);
            messageStringBuilder.set(result);
        }
        result.setLength(0);
        return result;
    }

    private static CharSequence toNullSafeString(final CharSequence s) {
        return s == null ? Strings.EMPTY : s;
    }

    /**
     * Non-private to make it accessible from unit test.
     */
    static CharSequence formatTimestamp(final long timeMillis) {
        if (timeMillis < 1000) {
            return "0";
        }
        final StringBuilder builder = getTimestampStringBuilder();
        builder.append(timeMillis);
        builder.insert(builder.length() - 3, '.');
        return builder;
    }

    private static final ThreadLocal<StringBuilder> timestampStringBuilder = new ThreadLocal<>();

    private static StringBuilder getTimestampStringBuilder() {
        StringBuilder result = timestampStringBuilder.get();
        if (result == null) {
            result = new StringBuilder(20);
            timestampStringBuilder.set(result);
        }
        result.setLength(0);
        return result;
    }

    /**
     * http://en.wikipedia.org/wiki/Syslog#Severity_levels
     */
    private int formatLevel(final Level level) {
        return Severity.getSeverity(level).getCode();
    }

    /**
     * Non-private to make it accessible from unit test.
     */
    static CharSequence formatThrowable(final Throwable throwable) {
        // stack traces are big enough to provide a reasonably large initial capacity here
        final StringWriter sw = new StringWriter(2048);
        final PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        pw.flush();
        return sw.getBuffer();
    }
}
