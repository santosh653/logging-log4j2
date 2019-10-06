package org.apache.logging.log4j.plugins.inject;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.plugins.Node;
import org.apache.logging.log4j.plugins.PluginAliases;
import org.apache.logging.log4j.plugins.bind.OptionBinder;
import org.apache.logging.log4j.plugins.name.AnnotatedElementNameProvider;
import org.apache.logging.log4j.status.StatusLogger;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public abstract class AbstractConfigurationInjector<Ann extends Annotation, Cfg> implements ConfigurationInjector<Ann, Cfg> {

    protected static final Logger LOGGER = StatusLogger.getLogger();

    protected Ann annotation;
    protected AnnotatedElement annotatedElement;
    protected Type conversionType;
    protected String name;
    protected Collection<String> aliases = Collections.emptyList();
    protected OptionBinder optionBinder;
    protected StringBuilder debugLog;
    protected Function<String, String> stringSubstitutionStrategy = Function.identity();
    protected Cfg configuration;
    protected Node node;

    @Override
    public ConfigurationInjector<Ann, Cfg> withAnnotation(final Ann annotation) {
        this.annotation = Objects.requireNonNull(annotation);
        return this;
    }

    @Override
    public ConfigurationInjector<Ann, Cfg> withAnnotatedElement(final AnnotatedElement element) {
        this.annotatedElement = Objects.requireNonNull(element);
        withName(AnnotatedElementNameProvider.getName(element));
        final PluginAliases aliases = element.getAnnotation(PluginAliases.class);
        if (aliases != null) {
            withAliases(aliases.value());
        }
        return this;
    }

    @Override
    public ConfigurationInjector<Ann, Cfg> withConversionType(final Type type) {
        this.conversionType = Objects.requireNonNull(type);
        return this;
    }

    @Override
    public ConfigurationInjector<Ann, Cfg> withName(final String name) {
        this.name = Objects.requireNonNull(name);
        return this;
    }

    @Override
    public ConfigurationInjector<Ann, Cfg> withAliases(final String... aliases) {
        this.aliases = Arrays.asList(aliases);
        return this;
    }

    @Override
    public ConfigurationInjector<Ann, Cfg> withOptionBinder(final OptionBinder binder) {
        this.optionBinder = binder;
        return this;
    }

    @Override
    public ConfigurationInjector<Ann, Cfg> withDebugLog(final StringBuilder debugLog) {
        this.debugLog = Objects.requireNonNull(debugLog);
        return this;
    }

    @Override
    public ConfigurationInjector<Ann, Cfg> withStringSubstitutionStrategy(final Function<String, String> strategy) {
        this.stringSubstitutionStrategy = Objects.requireNonNull(strategy);
        return this;
    }

    @Override
    public ConfigurationInjector<Ann, Cfg> withConfiguration(final Cfg configuration) {
        this.configuration = Objects.requireNonNull(configuration);
        return this;
    }

    @Override
    public ConfigurationInjector<Ann, Cfg> withNode(final Node node) {
        this.node = Objects.requireNonNull(node);
        return this;
    }

    protected Optional<String> findAndRemoveNodeAttribute() {
        Objects.requireNonNull(node);
        Objects.requireNonNull(name);
        final Map<String, String> attributes = node.getAttributes();
        for (final String key : attributes.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return Optional.ofNullable(attributes.remove(key));
            }
            for (final String alias : aliases) {
                if (key.equalsIgnoreCase(alias)) {
                    return Optional.ofNullable(attributes.remove(key));
                }
            }
        }
        return Optional.empty();
    }

}
