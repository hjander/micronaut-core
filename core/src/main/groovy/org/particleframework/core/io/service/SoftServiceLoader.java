/*
 * Copyright 2017 original authors
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.particleframework.core.io.service;

import org.particleframework.core.reflect.ClassUtils;
import org.particleframework.core.reflect.InstantiationUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * <p>Variation of {@link java.util.ServiceLoader} that allows soft loading classes</p>
 *
 * @author Graeme Rocher
 * @since 1.0
 */
public class SoftServiceLoader<S> implements Iterable<SoftServiceLoader.Service<S>> {
    public static final String META_INF_SERVICES = "META-INF/services";

    private final Class<S> serviceType;
    private final ClassLoader classLoader;
    private final Map<String,Service<S>> loadedServices = new LinkedHashMap<>();
    private final Iterator<Service<S>> unloadedServices;
    private final Predicate<String> condition;

    private SoftServiceLoader(Class<S> serviceType, ClassLoader classLoader) {
        this(serviceType, classLoader, (String name)-> true);

    }

    private SoftServiceLoader(Class<S> serviceType, ClassLoader classLoader, Predicate<String> condition) {
        this.serviceType = serviceType;
        this.classLoader = classLoader == null ? ClassLoader.getSystemClassLoader() : classLoader;
        this.unloadedServices = new ServiceLoaderIterator();
        this.condition = condition == null ? (String name)-> true : condition;

    }
    /**
     * Creates a new {@link SoftServiceLoader} using the thread context loader by default
     *
     * @return A new service loader
     */
    public static <S> SoftServiceLoader<S> load(Class<S> service) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return SoftServiceLoader.load(service, cl);
    }


    /**
     * Creates a new {@link SoftServiceLoader} using the given type and class loader
     *
     * @return A new service loader
     */
    public static <S> SoftServiceLoader<S> load(Class<S> service,
                                            ClassLoader loader)
    {
        return new SoftServiceLoader<>(service, loader);
    }

    /**
     * Creates a new {@link SoftServiceLoader} using the given type and class loader
     *
     * @return A new service loader
     */
    public static <S> SoftServiceLoader<S> load(Class<S> service,
                                                ClassLoader loader,
                                                Predicate<String> condition)
    {
        return new SoftServiceLoader<>(service, loader, condition);
    }

    @Override
    public Iterator<Service<S>> iterator() {
        return new Iterator<Service<S>>() {
            Iterator<Service<S>> loaded = loadedServices.values().iterator();
            @Override
            public boolean hasNext() {
                if(loaded.hasNext()) {
                    return true;
                }
                else if(unloadedServices.hasNext()) {
                    return true;
                }
                return false;
            }

            @Override
            public Service<S> next() {
                if(!hasNext()) throw new NoSuchElementException();

                if(loaded.hasNext()) {
                    return loaded.next();
                }
                else if(unloadedServices.hasNext()) {
                    return unloadedServices.next();
                }
                // should not happen
                throw new Error("Bug in iterator");
            }
        };
    }

    private final class ServiceLoaderIterator implements  Iterator<Service<S>> {
        private Enumeration<URL> serviceConfigs = null;
        private Iterator<String> unprocessed = null;
        @Override
        public boolean hasNext() {

            if(serviceConfigs == null) {
                String name = serviceType.getName();
                try {
                    serviceConfigs = classLoader.getResources(META_INF_SERVICES + '/' + name);
                } catch (IOException e) {
                    throw new ServiceConfigurationError("Failed to load resources for service: " + name, e);
                }
            }
            while(unprocessed == null || !unprocessed.hasNext() ) {
                if(!serviceConfigs.hasMoreElements()) {
                    return false;
                }
                URL url = serviceConfigs.nextElement();
                try {
                    try(BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()))) {
                        List<String> lines = reader.lines()
                                .filter((line) ->
                                        line.length() != 0 && line.charAt(0) != '#'
                                )
                                .filter(condition)
                                .map((line) -> {
                                    int i = line.indexOf('#');
                                    if (i > -1) {
                                        line = line.substring(0, i);
                                    }
                                    return line;
                                })
                                .collect(Collectors.toList());
                        unprocessed = lines.iterator();

                    }
                } catch (IOException e) {
                    throw new ServiceConfigurationError("Failed to load resources for URL: " + url, e);
                }
            }
            return unprocessed.hasNext();
        }

        @Override
        public Service<S> next() {
            if(!hasNext()) {
                throw new NoSuchElementException();
            }

            String nextName = unprocessed.next();
            Optional<Class> loadedClass = ClassUtils.forName(nextName, classLoader);
            return new Service<S>() {
                @Override
                public String getName() {
                    return nextName;
                }

                @Override
                public boolean isPresent() {
                    return loadedClass.isPresent();
                }

                @Override
                public <X extends Throwable> S orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
                    return InstantiationUtils.instantiate((Class<S>)loadedClass.orElseThrow(exceptionSupplier));
                }

                @Override
                public S load() {
                    return loadedClass.map(aClass -> InstantiationUtils.instantiate((Class<S>) aClass))
                                      .orElseThrow(()-> new ServiceConfigurationError("Call to load() when class '"+nextName+"' is not present"));
                }
            };
        }
    }

    public interface Service<T> {

        /**
         * @return The full class name of the service
         */
        String getName();
        /**
         * @return is the service present
         */
        boolean isPresent();

        /**
         * Load the service of throw the given exception
         *
         * @param exceptionSupplier The exception supplier
         * @param <X> The exception type
         * @return The instance
         * @throws X The exception concrete type
         */
        <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X;

        /**
         * @return load the service
         */
        T load();
    }
}
